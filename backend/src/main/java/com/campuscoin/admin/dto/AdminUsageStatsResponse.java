package com.campuscoin.admin.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "System-wide usage statistics (UC-23).")
public record AdminUsageStatsResponse(

        @Schema(description = "Accounts with the student role.", example = "42")
        Long totalStudents,

        @Schema(description = "Student accounts whose status is `ACTIVE`.", example = "40")
        Long activeStudents,

        @Schema(description = "Student accounts an administrator has disabled.", example = "2")
        Long disabledStudents,

        @Schema(description = "Distinct accounts with a session seen in the last 30 days.",
                example = "31")
        Long activeUsers30d,

        @Schema(description = "Transactions that are not in the trash (BR-09).", example = "517")
        Long totalTransactions,

        @Schema(description = "Total amount recorded against expense categories, across all "
                + "students. A sum, not any one student's spending.", example = "18420.50")
        BigDecimal totalExpenseLogged,

        @Schema(description = "Total amount recorded against income categories, across all "
                + "students. A sum, not any one student's income.", example = "96300.00")
        BigDecimal totalIncomeLogged,

        @Schema(description = "Spending limits set across all students.", example = "88")
        Long totalBudgets,

        @Schema(description = "Saving tips generated for students.", example = "204")
        Long totalTipsGenerated,

        @Schema(description = "Monthly insights generated. This is the table's own count, not a "
                + "hard-coded zero: a database seeded from `db/06_demo.sql` holds three, because the "
                + "demo data calls `sp_generate_monthly_insight` for three months. UC-17 is still "
                + "not enabled - no Java writes the table - so the figure does not move.",
                example = "3")
        Long totalInsightsGenerated) {
}
