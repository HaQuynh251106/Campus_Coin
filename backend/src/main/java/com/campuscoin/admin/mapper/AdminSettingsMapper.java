package com.campuscoin.admin.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.admin.dto.SystemSettingResponse;
import com.campuscoin.admin.service.AdminThresholds;
import com.campuscoin.common.setting.entity.SystemSetting;

@Component
public class AdminSettingsMapper {

    public SystemSettingResponse toResponse(SystemSetting setting) {
        return new SystemSettingResponse(
                setting.getSettingKey(),
                setting.getSettingValue(),
                setting.getValueType(),
                setting.getDescription(),
                AdminThresholds.isAdjustable(setting.getSettingKey()));
    }

    public List<SystemSettingResponse> toResponses(List<SystemSetting> settings) {
        return settings.stream().map(this::toResponse).toList();
    }
}
