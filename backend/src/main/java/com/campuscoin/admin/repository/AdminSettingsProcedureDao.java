package com.campuscoin.admin.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calls the procedure that changes one business threshold (UC-23, VĐ-05).
 *
 * <p><b>Why the setting is written by a procedure and not through the entity.</b>
 * {@code SystemSetting} is mapped, so {@code systemSetting.setSettingValue(...)} followed by a flush
 * would compile - and would bypass {@code sp_require_admin}, skip the key's allow-list, accept a value
 * the key cannot hold, and leave no {@code SETTING_CHANGED} audit row. The column is a plain
 * {@code VARCHAR(255)}, so nothing about the table itself would stop it. That is why
 * {@code SystemSetting}'s fields are read-only and why the entity is not reused here: reading the
 * settings list and changing one are different operations with different guarantees, and the second
 * one belongs to the procedure.
 *
 * <p>The procedure's three refusals - the key is not on its allow-list, the value does not fit the
 * key's shape, the key is not in {@code system_settings} - all arrive as SQLSTATE 45000, so the
 * exception alone does not say which input was wrong. The service checks the first two against
 * {@code AdminThresholds} before calling, which is what leaves each refusal identified; the third is
 * unreachable because every adjustable key is seeded.
 */
@Repository
public class AdminSettingsProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Sets a threshold to a new value (VĐ-05).
     *
     * <p>Three parameters, all always present, so the named shorthand binds them without type
     * declarations. {@code pValue} is passed as text because {@code setting_value} is a string and the
     * procedure casts it itself - the API deliberately does not pre-convert it to a number, so a value
     * the key cannot hold is refused by the same check every other caller goes through, and the stored
     * value is exactly the characters the caller sent.
     *
     * <p>The procedure records the change in {@code admin_audit_log} as {@code SETTING_CHANGED}, with
     * the previous and new values in {@code detail} and {@code target_id} left null - a setting is not
     * an entity with an id, and the key in the detail is what identifies it.
     *
     * <p>Not marked {@code readOnly}: it writes, and MySQL refuses any {@code CALL} on a read-only
     * connection in any case.
     *
     * @param actorId   the administrator making the change, re-checked by {@code sp_require_admin}
     * @param key       the setting's key, already checked against {@code AdminThresholds}
     * @param value     the new value as text, already checked to fit the key's shape
     * @param ipAddress client address, for the audit row
     */
    @Transactional
    public void setThreshold(Long actorId, String key, String value, String ipAddress) {
        entityManager.createNativeQuery(
                        "CALL sp_admin_set_threshold(:actorId, :key, :value, :ipAddress)")
                .setParameter("actorId", actorId)
                .setParameter("key", key)
                .setParameter("value", value)
                .setParameter("ipAddress", ipAddress)
                .executeUpdate();
    }
}
