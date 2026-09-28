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

@Service
public class AdminSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsService.class);

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

    @Transactional(readOnly = true)
    public List<SystemSettingResponse> list() {
        List<SystemSetting> settings =
                systemSettingRepository.findAll(Sort.by(Sort.Order.asc("settingKey")));
        return settingsMapper.toResponses(settings);
    }

    @Transactional
    public SystemSettingResponse update(String key, UpdateThresholdRequest request,
                                        Long actorId, String ipAddress) {

        if (!systemSettingRepository.existsById(key)) {
            throw new NotFoundException("Setting not found.");
        }

        if (!AdminThresholds.isAdjustable(key)) {
            log.info("Threshold change refused as not adjustable key={}", key);
            throw new ThresholdNotAdjustableException(
                    "The setting '" + key + "' is not one this API can change.");
        }

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

    private SystemSetting readBack(String key) {
        return systemSettingRepository.findBySettingKey(key)
                .orElseThrow(() -> new IllegalStateException(
                        "Setting '" + key + "' was not readable back after update."));
    }

    private RuntimeException translateWriteFailure(RuntimeException ex, String key) {
        if (AdminWriteFailure.isSignalledRefusal(ex) || AdminWriteFailure.isConstraintViolation(ex)) {
            log.info("Setting write refused key={}", key);
            return new DataConflictException(RELOAD_AND_RETRY);
        }

        log.error("Setting write failed unexpectedly key={}", key, ex);
        return ex;
    }
}
