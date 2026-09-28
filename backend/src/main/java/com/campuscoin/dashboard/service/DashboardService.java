package com.campuscoin.dashboard.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.setting.SettingReader;
import com.campuscoin.dashboard.dto.DashboardResponse;
import com.campuscoin.dashboard.entity.DashboardSummary;
import com.campuscoin.dashboard.mapper.DashboardMapper;
import com.campuscoin.dashboard.repository.DashboardViewDao;

@Service
public class DashboardService {

    private final DashboardViewDao dashboardViewDao;
    private final DashboardMapper dashboardMapper;
    private final SettingReader settingReader;

    public DashboardService(DashboardViewDao dashboardViewDao, DashboardMapper dashboardMapper,
                            SettingReader settingReader) {
        this.dashboardViewDao = dashboardViewDao;
        this.dashboardMapper = dashboardMapper;
        this.settingReader = settingReader;
    }

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(AuthenticatedUser principal) {
        Long userId = principal.userId();

        DashboardSummary summary = dashboardViewDao.findSummary(userId)
                .orElseThrow(() -> new NotFoundException("Dashboard not found."));

        int maxTips = settingReader.getInt(SettingReader.TIPS_MAX_DASHBOARD,
                SettingReader.DEFAULT_TIPS_MAX_DASHBOARD);

        return dashboardMapper.toResponse(
                summary.periodMonth(),
                summary,
                dashboardViewDao.findTopCategory(userId).orElse(null),
                dashboardViewDao.findTipsLimited(userId, summary.periodMonth(), maxTips),
                dashboardViewDao.findAnnouncementsForStudent());
    }
}
