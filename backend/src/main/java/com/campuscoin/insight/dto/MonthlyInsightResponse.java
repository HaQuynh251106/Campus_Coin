package com.campuscoin.insight.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.campuscoin.insight.entity.InsightGeneratedBy;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One month's insight for the signed-in student, with its figures and the source "
        + "of its wording (UC-17). The summary and advice are advisory: BR-13 requires a client to "
        + "present them as a suggestion the student reviews, to label them as a suggestion rather than "
        + "as financial advice, and to use `generatedBy` to say where the words came from.")
public record MonthlyInsightResponse(

        @Schema(description = "The month this insight describes, as `yyyy-MM`. Echoed back so a client "
                + "can confirm which month was answered.", example = "2026-09")
        String periodMonth,

        @Schema(description = "The month's total income, as the database computed it when the insight "
                + "was generated.", example = "260.00")
        BigDecimal totalIncome,

        @Schema(description = "The month's total spending.", example = "189.00")
        BigDecimal totalExpense,

        @Schema(description = "Income minus spending. Negative means the student spent more than they "
                + "received that month.", example = "71.00")
        BigDecimal netAmount,

        @Schema(description = "What the month looked like, in prose. Written by a provider when "
                + "`generatedBy` is `AI`, and composed from the figures above by a fixed rule when it "
                + "is `RULE_BASED`. Advisory: a suggestion, not financial advice (BR-13).",
                example = "2026-09: you received 260.00 and spent 189.00, net difference 71.00. "
                        + "Highest spending category: Food (95.00).")
        String summary,

        @Schema(description = "What the student might consider next month, framed as a suggestion. "
                + "Written by the same author as `summary`, and advisory for the same reason (BR-13).",
                example = "You are keeping a positive balance. Consider moving the surplus toward your "
                        + "savings goal at the start of the month.")
        String advice,

        @Schema(description = "Who wrote the prose: `AI` when a provider did, `RULE_BASED` when the "
                + "database composed it from the student's own figures by a fixed rule. The value a "
                + "client switches on to label the text honestly (BR-13).", example = "RULE_BASED")
        InsightGeneratedBy generatedBy,

        @Schema(description = "The provider's model identifier, stored when it wrote the text. Omitted "
                + "when no provider was involved.", example = "gemini-3.5-flash", nullable = true)
        String model,

        @Schema(description = "Expense categories that ran above the student's own usual level that "
                + "month, as the database flagged them (BR-15). Empty when nothing stood out, which is "
                + "the ordinary case.")
        List<FlaggedCategoryResponse> flaggedCategories,

        @Schema(description = "When this insight was generated. An insight is a snapshot of a month, so "
                + "this says whether it reflects a correction the student made afterwards.",
                example = "2026-09-26T20:14:05")
        LocalDateTime generatedAt) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "An expense category that ran above the student's own usual level (UC-17, "
            + "BR-15).")
    public record FlaggedCategoryResponse(

            @Schema(description = "Identifier of the category (BR-05).", example = "4")
            Long categoryId,

            @Schema(description = "Name of that category.", example = "Food")
            String categoryName,

            @Schema(description = "What was spent in it that month.", example = "340.00")
            BigDecimal currentTotal,

            @Schema(description = "What the student usually spent in it over the preceding months - "
                    + "their own average, not a limit.", example = "110.00")
            BigDecimal baselineAvg,

            @Schema(description = "How far above the usual level that month was, as a percentage. "
                    + "Omitted when there was nothing to compare against.", example = "209.09",
                    nullable = true)
            BigDecimal pctChange) {
    }
}
