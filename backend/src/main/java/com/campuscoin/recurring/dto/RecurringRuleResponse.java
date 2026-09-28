package com.campuscoin.recurring.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.recurring.entity.RecurringFrequency;
import com.campuscoin.recurring.entity.RecurringStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A recurring rule (UC-09).")
public record RecurringRuleResponse(

        @Schema(description = "Identifier, as the database assigned it.", example = "4")
        Long id,

        @Schema(description = "The category this rule posts under.", example = "1")
        Long categoryId,

        @Schema(description = "Name of that category.", example = "Allowance")
        String categoryName,

        @Schema(description = "Icon name of that category, for the client to resolve. Omitted when "
                + "the category has none.", example = "wallet", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String categoryIcon,

        @Schema(description = "Hex colour of that category. Omitted when the category has none.",
                example = "#22C55E", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String categoryColor,

        @Schema(description = "Whether this rule posts income or expense. Derived from the category "
                + "and read-only (BR-05).", example = "INCOME")
        CategoryType type,

        @Schema(description = "How much each occurrence posts. Always positive; `type` says which "
                + "direction.", example = "200.00")
        BigDecimal amount,

        @Schema(description = "Note copied onto each generated transaction. Omitted when not set.",
                example = "Monthly allowance", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String description,

        @Schema(description = "How often the rule posts.", example = "MONTHLY")
        RecurringFrequency frequency,

        @Schema(description = "How many periods between occurrences. 2 with `MONTHLY` is every "
                + "other month.", example = "1")
        Integer intervalCount,

        @Schema(description = "The date the rule started from. Not editable.",
                example = "2026-09-01")
        LocalDate startDate,

        @Schema(description = "The date the rule stops, inclusive. Omitted when the rule is "
                + "open-ended.", example = "2027-06-01", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDate endDate,

        @Schema(description = "The date of the next occurrence. This is what the scheduler reads "
                + "and what an update moves.", example = "2026-10-01")
        LocalDate nextRunDate,

        @Schema(description = "The date of the most recent occurrence the scheduler posted. Omitted "
                + "for a rule that has not run yet, which is how a client tells a new rule from a "
                + "running one.", example = "2026-09-01", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDate lastRunDate,

        @Schema(description = "`ACTIVE` posts on schedule, `PAUSED` is stopped but kept, `ENDED` is "
                + "finished. Only `ACTIVE` rules are picked up by the scheduler.", example = "ACTIVE")
        RecurringStatus status) {
}
