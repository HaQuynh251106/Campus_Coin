package com.campuscoin.admin.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.dto.SystemSettingResponse;
import com.campuscoin.admin.dto.UpdateThresholdRequest;
import com.campuscoin.admin.mapper.AdminSettingsMapper;
import com.campuscoin.admin.repository.AdminSettingsProcedureDao;
import com.campuscoin.common.exception.DataConflictException;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.ThresholdNotAdjustableException;
import com.campuscoin.common.setting.entity.SystemSetting;
import com.campuscoin.common.setting.repository.SystemSettingRepository;

/**
 * The business thresholds an administrator may tune (UC-23, VĐ-05).
 *
 * <p><b>Two populations live in {@code system_settings}, and this class serves both by name.</b>
 * Sixteen rows are seeded. Six of them are business thresholds that a running installation is expected
 * to tune - the two budget percentages, the two spike-detection values, the dashboard tip count and the
 * reset-token lifetime - and {@code sp_admin_set_threshold} whitelists exactly those. The other ten are
 * deployment configuration ({@code app.currency}, {@code app.timezone}), authentication policy read by
 * Java, or anomaly and AI values belonging to capabilities this build does not have.
 *
 * <p><b>The list publishes all sixteen and marks six.</b> Hiding the read-only rows would leave an
 * administrator unable to see the currency a deployment runs in or the session lifetime in force -
 * facts about the system they administer. So {@code GET} returns every row with an {@code adjustable}
 * flag, and {@code PATCH} refuses the rest. Both answers come from {@link AdminThresholds}, which is
 * the single statement of the six, so a row cannot be listed as changeable and then refused, or the
 * reverse.
 *
 * <p><b>The list is the one read in this module that goes through a mapped entity</b>, because
 * {@code common.setting} already owns {@link SystemSetting} and the repository beside it; a native
 * projection here would be a second description of the same table. That entity is read-only in this
 * module - it has no setter use anywhere in {@code com.campuscoin.admin} - and the write below is a
 * {@code CALL}, so the entity cannot be a way around {@code sp_require_admin}.
 */
@Service
public class AdminSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsService.class);

    /**
     * What a refused threshold change says when the refusal is not about the key or the value.
     *
     * <p>Both of those are answered before the call, so this covers only the actor being refused by
     * {@code sp_require_admin} - a role or status this request cannot see.
     */
    private static final String RELOAD_AND_RETRY =
            "The setting could not be changed. Reload it and try again.";

    private final SystemSettingRepository systemSettingRepository;
    private final AdminSettingsProcedureDao settingsProcedureDao;
    private final AdminSettingsMapper settingsMapper;

    public AdminSettingsService(SystemSettingRepository systemSettingRepository,
                                AdminSettingsProcedureDao settingsProcedureDao,
                                AdminSettingsMapper settingsMapper) {
        this.systemSettingRepository = systemSettingRepository;
        this.settingsProcedureDao = settingsProcedureDao;
        this.settingsMapper = settingsMapper;
    }

    /**
     * UC-23 / VĐ-05: every setting, with the flag that says which of them this API may change.
     *
     * <p>Ordered by key explicitly rather than relying on the order the table happens to return. The
     * order is the key's alphabetical order, which groups {@code app.*}, {@code auth.*},
     * {@code budget.*} and the rest together, and it is stable between two identical calls - which a
     * primary-key order is not guaranteed to be, because {@code setting_key} is the primary key here and
     * the storage engine's return order is not part of the contract.
     */
    @Transactional(readOnly = true)
    public List<SystemSettingResponse> list() {
        List<SystemSetting> settings =
                systemSettingRepository.findAll(Sort.by(Sort.Order.asc("settingKey")));
        return settingsMapper.toResponses(settings);
    }

    /**
     * UC-23 / VĐ-05: change one threshold.
     *
     * <p><b>Both of the procedure's own refusals are pre-answered here, and the order is not the
     * procedure's order.</b> {@code sp_admin_set_threshold} checks the key allow-list, then the value's
     * shape, then whether the row exists. This method checks existence, then adjustability, then shape.
     * The difference is deliberate: an id in the path that names no row is a {@code 404}, which is more
     * precise than the procedure's answer for that case - it reports "this key cannot be changed through
     * this procedure", which is true but says nothing about the row not being there. Answering by the
     * more specific fact costs nothing, because a caller who reaches the procedure has already passed
     * all three checks.
     *
     * <p><b>What the pre-checks buy is a classifiable signal.</b> {@code sp_require_admin} and the
     * procedure's own refusals all raise SQLSTATE 45000, so a signal that reached this method without
     * them would be indistinguishable from an authorisation failure by anything other than the
     * procedure's prose - which this project forbids matching. With the three checks above, the only
     * refusal left is the actor's, and it is answered as a conflict.
     *
     * <p><b>{@code Adjustable} is the rule, not a suggestion.</b> A key that exists but is not one of the
     * six is refused with {@code THRESHOLD_NOT_ADJUSTABLE} rather than passed to the procedure to be
     * refused there: the row was reported as unchangeable by the list endpoint, and a request that
     * ignored the flag should be told why rather than given a generic conflict.
     *
     * @throws NotFoundException              no setting row has this key
     * @throws ThresholdNotAdjustableException the row exists but is not one of the six tunable settings
     * @throws com.campuscoin.common.exception.RequestValidationException the value does not fit the key
     */
    @Transactional
    public SystemSettingResponse update(String key, UpdateThresholdRequest request,
                                        Long actorId, String ipAddress) {
        // Existence first, as a boolean: reading the entity here would place it in the persistence
        // context, and the read-back after the write would then return that same instance with the old
        // value, because the procedure's native UPDATE is invisible to Hibernate.
        if (!systemSettingRepository.existsById(key)) {
            throw new NotFoundException("Setting not found.");
        }

        if (!AdminThresholds.isAdjustable(key)) {
            log.info("Threshold change refused as not adjustable key={}", key);
            throw new ThresholdNotAdjustableException(
                    "The setting '" + key + "' is not one this API can change.");
        }

        // The same shape check the procedure makes, reported as a field error instead of a signal.
        AdminThresholds.assertValueFits(key, request.value());

        String value = request.value().trim();

        try {
            settingsProcedureDao.setThreshold(actorId, key, value, ipAddress);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, key);
        }

        log.info("Setting changed actorId={} key={}", actorId, key);

        return settingsMapper.toResponse(readBack(key));
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /** The row just written, or a fault: it was confirmed to exist moments ago in this transaction. */
    private SystemSetting readBack(String key) {
        return systemSettingRepository.findBySettingKey(key)
                .orElseThrow(() -> new IllegalStateException(
                        "Setting '" + key + "' was not readable back after update."));
    }

    /**
     * Turns a database refusal into the API error that explains it.
     *
     * <p>One branch, because the three refusals the procedure makes itself were answered before the
     * call. What is left is {@code sp_require_admin} refusing the actor - answered as a conflict, since
     * the caller's request is not wrong and changing the key or the value would not help.
     */
    private RuntimeException translateWriteFailure(RuntimeException ex, String key) {
        if (AdminWriteFailure.isSignalledRefusal(ex) || AdminWriteFailure.isConstraintViolation(ex)) {
            log.info("Setting write refused key={}", key);
            return new DataConflictException(RELOAD_AND_RETRY);
        }

        log.error("Setting write failed unexpectedly key={}", key, ex);
        return ex;
    }
}
