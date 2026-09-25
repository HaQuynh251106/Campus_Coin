package com.campuscoin.admin.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.StoredProcedureQuery;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.category.entity.CategoryType;

/**
 * Calls the procedure that creates and edits a shared default category (UC-20, BR-06).
 *
 * <p><b>Why this is a procedure and not an entity write.</b> {@link com.campuscoin.category.entity.Category}
 * is a mapped entity, so an administrative create could be written as {@code save} with
 * {@code userId} left null - and it would bypass {@code sp_require_admin}, leave no
 * {@code admin_audit_log} row, and run the insert trigger with whatever {@code created_by} the caller
 * supplied. BR-06 requires that only an active administrator can create a default category, which
 * means the authorisation has to be part of the write; {@code sp_admin_upsert_default_category} is
 * what makes it so, because it calls {@code sp_require_admin} before it touches the table. That is
 * also why {@code CategoryService}'s legitimate {@code save} for personal rows is not reused here:
 * the two rows differ in exactly the thing that matters.
 *
 * <p><b>Every optional parameter is nullable, and that is the procedure's contract.</b> Passing
 * {@code NULL} for a field means "leave it alone" - the update branch writes each column as
 * {@code IFNULL(p_x, x)}. Passing {@code NULL} for {@code p_category_id} means "insert". Those two
 * nulls carry opposite instructions, so the DAO binds them explicitly rather than leaving either
 * implicit.
 *
 * <p><b>Which is why this class declares its parameter types.</b> The named-parameter shorthand
 * {@code createNativeQuery("CALL ...").setParameter("x", null)} cannot bind a null: without a
 * declared type there is nothing for the driver to send, so the call fails before it reaches the
 * procedure. Declaring every parameter's mode and type is also what lets the intentionally-null ones
 * be distinguished from a mistake, since a required parameter that was accidentally left null then
 * fails against the procedure's own {@code IF p_name IS NULL} check rather than silently binding as
 * an untyped null.
 */
@Repository
public class AdminCategoryProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Creates a default category, or replaces the fields of an existing one.
     *
     * <p>One method for both because the procedure is one call: with a null
     * {@code categoryId} it inserts, and with an id it updates. Splitting it into two Java methods
     * would put the choice between them in the service, where it could disagree with what the
     * procedure actually does.
     *
     * <p>The procedure writes an audit row naming the resource - {@code CATEGORY_CREATED} or
     * {@code CATEGORY_UPDATED} - and its {@code target_id} is the id of the category row, whether it
     * was just inserted (the procedure reads {@code LAST_INSERT_ID()}) or passed in. That matters to
     * the caller: after an insert there is no OUT parameter and no reliable
     * {@code LAST_INSERT_ID()} to read back here, because the procedure's own audit insert runs after
     * the category insert and leaves <em>its</em> id as the session's last insert id. The created row
     * is therefore located by its natural key - the unique {@code (type, name)} among default rows -
     * not by the last insert id. See {@code docs/modules/MODULE_11_ADMINISTRATION.md}.
     *
     * @param actorId     the administrator making the change, re-checked by {@code sp_require_admin}
     * @param categoryId  {@code null} to create a new row, or the id of a default row to update
     * @param name        required on create; null leaves an existing row's name unchanged
     * @param type        required on create; null leaves an existing row's type unchanged
     * @param icon        null leaves the value unchanged - and, on update, cannot clear it, because
     *                    the procedure writes {@code IFNULL(p_icon, icon)}
     * @param color       as {@code icon}: null means unchanged, never cleared
     * @param description as {@code icon}: null means unchanged, never cleared
     * @param sortOrder   as {@code icon}: null means unchanged
     * @param isActive    as {@code icon}: null means unchanged; false retires the category (BR-07)
     * @param ipAddress   client address, for the audit row
     */
    @Transactional
    public void upsertDefaultCategory(Long actorId,
                                      Long categoryId,
                                      String name,
                                      CategoryType type,
                                      String icon,
                                      String color,
                                      String description,
                                      Integer sortOrder,
                                      Boolean isActive,
                                      String ipAddress) {
        StoredProcedureQuery query = entityManager.createStoredProcedureQuery(
                "sp_admin_upsert_default_category");

        // Declared one by one, in the procedure's own order, because the call is positional: a
        // parameter registered in the wrong position would be bound to the wrong column.
        query.registerStoredProcedureParameter(1, Long.class, ParameterMode.IN);      // p_actor_id
        query.registerStoredProcedureParameter(2, Long.class, ParameterMode.IN);      // p_category_id
        query.registerStoredProcedureParameter(3, String.class, ParameterMode.IN);    // p_name
        query.registerStoredProcedureParameter(4, String.class, ParameterMode.IN);    // p_type
        query.registerStoredProcedureParameter(5, String.class, ParameterMode.IN);    // p_icon
        query.registerStoredProcedureParameter(6, String.class, ParameterMode.IN);    // p_color
        query.registerStoredProcedureParameter(7, String.class, ParameterMode.IN);    // p_description
        query.registerStoredProcedureParameter(8, Short.class, ParameterMode.IN);     // p_sort_order
        query.registerStoredProcedureParameter(9, Boolean.class, ParameterMode.IN);   // p_is_active
        query.registerStoredProcedureParameter(10, String.class, ParameterMode.IN);   // p_ip

        query.setParameter(1, actorId);
        query.setParameter(2, categoryId);
        query.setParameter(3, name);
        query.setParameter(4, type == null ? null : type.name());
        query.setParameter(5, icon);
        query.setParameter(6, color);
        query.setParameter(7, description);
        query.setParameter(8, sortOrder == null ? null : sortOrder.shortValue());
        query.setParameter(9, isActive);
        query.setParameter(10, ipAddress);

        query.executeUpdate();
    }
}
