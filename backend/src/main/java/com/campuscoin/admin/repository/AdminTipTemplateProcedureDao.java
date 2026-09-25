package com.campuscoin.admin.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.StoredProcedureQuery;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.entity.TipConditionType;

/**
 * Calls the procedure that creates and edits a saving-tip template (UC-21 B3, B4).
 *
 * <p><b>The update branch cannot change {@code code}, and a client that sends one is not told.</b>
 * {@code sp_admin_upsert_tip_template} writes {@code title_template}, {@code body_template},
 * {@code condition_type}, {@code default_priority} and {@code is_active} on update - and does not write
 * {@code code} at all. A {@code PATCH} carrying a different code would therefore succeed and silently
 * ignore it, leaving a client that was told "saved" with a code that never moved. The service reads the
 * row first and answers {@code 409 TIP_TEMPLATE_CODE_IMMUTABLE} for a differing code, accepting an
 * equal one as a no-op so a client can round-trip a full representation; this DAO simply never passes a
 * code on the update path, so the class does not restate the rule the procedure already embodies.
 *
 * <p>No entity and no {@code save}, for the reason the announcement DAO gives: the procedure is the
 * only writer, so {@code sp_require_admin} and the audit row always run.
 */
@Repository
public class AdminTipTemplateProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Creates a template, or replaces the editable fields of an existing one.
     *
     * <p>One method for both, because the procedure is one call: a null {@code templateId} inserts and
     * an id updates. Splitting it would put the choice in Java, where it could disagree with what the
     * procedure actually does.
     *
     * <p><b>{@code code} is only meaningful on create.</b> The parameter is always sent - the procedure
     * declares it - but on the update path the procedure does not read it to write the column, and the
     * service has already refused any value that differs from the stored one. Sending the stored code
     * unchanged is therefore what happens on a successful update.
     *
     * <p><b>{@code conditionType} and {@code defaultPriority} being null means "let the procedure
     * decide" on insert and "leave unchanged" on update</b>, because the two branches interpret null
     * differently: the insert uses {@code IFNULL(p_condition_type, 'GENERIC')} and
     * {@code IFNULL(p_default_priority, 100)}, while the update uses {@code IFNULL(p_x, x)}. Passing
     * null through rather than substituting a value here keeps both behaviours in the procedure.
     *
     * <p>Returns nothing: the procedure has no OUT parameter, and {@code LAST_INSERT_ID()} after the
     * call belongs to its audit row. The caller reads the created template back by its unique
     * {@code code} through {@code AdminTipTemplateViewDao.findByCode}.
     *
     * <p>Audit row: {@code TIP_TEMPLATE_SAVED}, whose {@code target_id} is the template id in both
     * branches.
     *
     * @param actorId          the administrator making the change, re-checked by {@code sp_require_admin}
     * @param templateId       {@code null} to create a new template, or the id of one to update
     * @param code             required on create - the procedure refuses a null code; ignored on update
     * @param conditionType    null means the procedure's own fallback: {@code GENERIC} on create,
     *                         unchanged on update
     * @param titleTemplate    required on create; null leaves an existing template's title unchanged
     * @param bodyTemplate     required on create; null leaves an existing template's body unchanged
     * @param defaultPriority  null means the procedure's own fallback: 100 on create, unchanged on
     *                         update
     * @param isActive         null means unchanged on update; true by default on create
     * @param ipAddress        client address, for the audit row
     */
    @Transactional
    public void upsertTipTemplate(Long actorId,
                                  Long templateId,
                                  String code,
                                  TipConditionType conditionType,
                                  String titleTemplate,
                                  String bodyTemplate,
                                  Integer defaultPriority,
                                  Boolean isActive,
                                  String ipAddress) {
        StoredProcedureQuery query = entityManager.createStoredProcedureQuery(
                "sp_admin_upsert_tip_template");

        // Declared one by one, in the procedure's own order, because the call is positional.
        query.registerStoredProcedureParameter(1, Long.class, ParameterMode.IN);      // p_actor_id
        query.registerStoredProcedureParameter(2, Long.class, ParameterMode.IN);      // p_template_id
        query.registerStoredProcedureParameter(3, String.class, ParameterMode.IN);    // p_code
        query.registerStoredProcedureParameter(4, String.class, ParameterMode.IN);    // p_condition_type
        query.registerStoredProcedureParameter(5, String.class, ParameterMode.IN);    // p_title_template
        query.registerStoredProcedureParameter(6, String.class, ParameterMode.IN);    // p_body_template
        query.registerStoredProcedureParameter(7, Short.class, ParameterMode.IN);     // p_default_priority
        query.registerStoredProcedureParameter(8, Boolean.class, ParameterMode.IN);   // p_is_active
        query.registerStoredProcedureParameter(9, String.class, ParameterMode.IN);    // p_ip

        query.setParameter(1, actorId);
        query.setParameter(2, templateId);
        query.setParameter(3, code);
        query.setParameter(4, conditionType == null ? null : conditionType.name());
        query.setParameter(5, titleTemplate);
        query.setParameter(6, bodyTemplate);
        query.setParameter(7, defaultPriority == null ? null : defaultPriority.shortValue());
        query.setParameter(8, isActive);
        query.setParameter(9, ipAddress);

        query.executeUpdate();
    }
}
