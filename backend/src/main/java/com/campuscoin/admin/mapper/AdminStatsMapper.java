package com.campuscoin.admin.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.admin.dto.AdminTopCategoryResponse;
import com.campuscoin.admin.dto.AdminUsageStatsResponse;
import com.campuscoin.admin.entity.AdminTopCategoryRow;
import com.campuscoin.admin.entity.AdminUsageStats;

@Component
public class AdminStatsMapper {

    public AdminUsageStatsResponse toResponse(AdminUsageStats stats) {
        return new AdminUsageStatsResponse(
                stats.totalStudents(),
                stats.activeStudents(),
                stats.disabledStudents(),
                stats.activeUsers30d(),
                stats.totalTransactions(),
                stats.totalExpenseLogged(),
                stats.totalIncomeLogged(),
                stats.totalBudgets(),
                stats.totalTipsGenerated(),
                stats.totalInsightsGenerated());
    }

    public AdminTopCategoryResponse toResponse(AdminTopCategoryRow row) {
        return new AdminTopCategoryResponse(
                row.categoryId(),
                row.categoryName(),
                row.type(),
                row.scope(),
                row.txnCount(),
                row.totalAmount(),
                row.distinctUsers());
    }

    public List<AdminTopCategoryResponse> toResponses(List<AdminTopCategoryRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }
}
