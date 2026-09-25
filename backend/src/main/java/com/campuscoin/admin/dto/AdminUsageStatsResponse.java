package com.campuscoin.admin.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * System-wide usage figures (UC-23).
 *
 * <p><b>Every field is an aggregate and none of them is about an identifiable student.</b> The
 * counts are totals over the whole user base and the two money figures are sums across every
 * student's transactions. That distinction is the reason this endpoint can publish money at all: no
 * route in this module returns one student's amounts, so the most a reader learns from
 * {@code totalExpenseLogged} is what the whole system recorded. See
 * {@code docs/OVERNIGHT_BLOCKERS.md} OB-013 for the recorded position on amounts remaining plaintext
 * in the database.
 *
 * <p>{@code activeUsers30d} is "distinct accounts with a session seen in the last 30 days", which is
 * the view's definition and is deliberately not the same as {@code activeStudents}: an account is
 * active by status until an administrator disables it, whereas this figure is about use. Both are
 * published because UC-23 asks how many students there are and how many are actually using the
 * system, and those are different questions.
 *
 * <p>{@code totalInsightsGenerated} is carried although this build never generates an insight - the
 * table is empty and the figure is a true zero. Reporting the view's own column keeps this response
 * the view's numbers rather than a selection of them.
 */
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
