package com.campuscoin.admin.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.admin.dto.SystemSettingResponse;
import com.campuscoin.admin.service.AdminThresholds;
import com.campuscoin.common.setting.entity.SystemSetting;

/**
 * Maps a setting row to the API model (UC-23, VĐ-05).
 *
 * <p><b>This class computes the {@code adjustable} flag, and that is why it takes a dependency.</b>
 * The flag is answered by {@link AdminThresholds#isAdjustable}, which is the same list the update
 * endpoint enforces. Computing it here rather than passing it in from the service keeps the decision
 * beside the field it describes: a caller reading {@code SystemSettingResponse} sees where its value
 * comes from, and there is no path by which a row could be listed with one flag and enforced with
 * another.
 *
 * <p>{@code updatedBy} and {@code updatedAt} are deliberately not published, even though the entity
 * carries them. Who changed a threshold and when is what {@code admin_audit_log} records - that is
 * UC-22 B5's requirement, and the {@code SETTING_CHANGED} rows already carry the previous and new
 * values. A second copy on the row itself would be a weaker answer to the same question and would
 * invite a client to treat it as the authority.
 */
@Component
public class AdminSettingsMapper {

    /**
     * VĐ-05: one setting, with the flag that says whether this API may change it.
     *
     * <p>{@code value} passes through as the stored string. Interpreting it belongs to {@code valueType}
     * and to the caller: {@code app.currency} is {@code USD} and {@code app.timezone} is a zone name,
     * so a mapper that parsed it into a number would have to special-case most of the rows.
     */
    public SystemSettingResponse toResponse(SystemSetting setting) {
        return new SystemSettingResponse(
                setting.getSettingKey(),
                setting.getSettingValue(),
                setting.getValueType(),
                setting.getDescription(),
                AdminThresholds.isAdjustable(setting.getSettingKey()));
    }

    /**
     * VĐ-05: every setting, in the order {@code SystemSettingRepository#findAll} returns them.
     *
     * <p>No reordering here. The rows come back in primary-key order, which is the key's alphabetical
     * order and therefore groups {@code app.*}, {@code auth.*}, {@code budget.*} and the rest together
     * - useful on an administration screen, and stable between calls without this class imposing
     * anything.
     */
    public List<SystemSettingResponse> toResponses(List<SystemSetting> settings) {
        return settings.stream().map(this::toResponse).toList();
    }
}
