package com.campuscoin.admin.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.admin.dto.AdminTopCategoryResponse;
import com.campuscoin.admin.dto.AdminUsageStatsResponse;
import com.campuscoin.admin.entity.AdminTopCategoryRow;
import com.campuscoin.admin.entity.AdminUsageStats;

/**
 * Maps the two administration figures to the API models (UC-23).
 *
 * <p>This mapper changes no value: every field is an aggregate the database already computed, and the
 * response is the view's numbers. It exists for the reason the other mappers do - to be the one place
 * the published field set is written down - and because the two shapes are separate responses rather
 * than one, so a later change to either is visible here.
 *
 * <p><b>It deliberately does not round, format or rescale the money figures.</b> They are
 * {@code BigDecimal} sums over plaintext amounts (OB-013), and a mapper that applied a scale or a
 * currency symbol would be presenting them as something other than what the database holds. Formatting
 * belongs to the client, which is the only layer that knows the reader's locale.
 */
@Component
public class AdminStatsMapper {

    /**
     * UC-23: the one aggregate row.
     *
     * <p>A straight pass-through of ten values. {@code totalInsightsGenerated} is carried although it
     * is a true zero in this build - the {@code insights} table is empty because UC-17 is not enabled -
     * so the response reports the same numbers the view does rather than a selection of them.
     */
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

    /**
     * UC-23: one ranking row.
     *
     * <p>{@code scope} passes through as the view's own {@code 'DEFAULT'} or {@code 'PERSONAL'} rather
     * than being recomputed from an owner, because the view's definition of scope and this module's are
     * the same definition and re-deriving it here would be a second one.
     */
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

    /**
     * UC-23: the whole ranking.
     *
     * <p>The order is the DAO's, not this method's - most-used first, with the category id as the
     * final tie-break - and it is not disturbed here. A mapper that sorted would have to restate the
     * comparison the DAO already applied.
     */
    public List<AdminTopCategoryResponse> toResponses(List<AdminTopCategoryRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }
}
