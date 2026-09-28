package com.campuscoin.admin.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.dto.AdminTopCategoryResponse;
import com.campuscoin.admin.dto.AdminUsageStatsResponse;
import com.campuscoin.admin.mapper.AdminStatsMapper;
import com.campuscoin.admin.repository.AdminStatsViewDao;

@Service
public class AdminStatsService {

    private final AdminStatsViewDao statsViewDao;
    private final AdminStatsMapper statsMapper;

    public AdminStatsService(AdminStatsViewDao statsViewDao, AdminStatsMapper statsMapper) {
        this.statsViewDao = statsViewDao;
        this.statsMapper = statsMapper;
    }

    @Transactional(readOnly = true)
    public AdminUsageStatsResponse usageStats() {

        return statsViewDao.findUsageStats()
                .map(statsMapper::toResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "v_admin_usage_stats returned no row; it is defined to return exactly one."));
    }

    @Transactional(readOnly = true)
    public List<AdminTopCategoryResponse> topCategories() {
        return statsMapper.toResponses(statsViewDao.findTopCategories());
    }
}
