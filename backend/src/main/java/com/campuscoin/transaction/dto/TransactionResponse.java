package com.campuscoin.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.transaction.entity.TransactionSource;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A recorded income or expense (UC-07, UC-10).")
public record TransactionResponse(

        @Schema(description = "Identifier, as the database assigned it. The value in this example is "
                + "an illustration of the type, not a row that exists in every database; take the id "
                + "from a response rather than assuming a value.", example = "31")
        Long id,

        @Schema(description = "The category this is filed under. The pair in this example is a real "
                + "one: `6` is `Food`, the first expense category `db/05_seed.sql` creates.",
                example = "6")
        Long categoryId,

        @Schema(description = "Name of that category.", example = "Food")
        String categoryName,

        @Schema(description = "Icon name of that category, for the client to resolve. Omitted when "
                + "the category has none.", example = "utensils", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String categoryIcon,

        @Schema(description = "Hex colour of that category. Omitted when the category has none.",
                example = "#F97316", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String categoryColor,

        @Schema(description = "Whether this counts as income or expense. Derived from the category "
                + "and read-only: `transactions` has no type column, because keeping one source of "
                + "truth is what guarantees the two agree (BR-05).", example = "EXPENSE")
        CategoryType type,

        @Schema(description = "How much moved. Always positive; `type` says which direction.",
                example = "12.50")
        BigDecimal amount,

        @Schema(description = "The date the money moved.", example = "2026-09-24")
        LocalDate txnDate,

        @Schema(description = "Optional note. Omitted when not set.",
                example = "Lunch with the study group", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String description,

        @Schema(description = "How the record came to exist. Always `MANUAL` for a row created "
                + "through this API; `CSV` (UC-11) and `RECURRING` (UC-09) belong to later modules. "
                + "Read-only: a client able to claim `RECURRING` could record a future-dated "
                + "transaction, since that is the one source exempt from the BR-08 date check.",
                example = "MANUAL")
        TransactionSource source,

        @Schema(description = "True when the record is in the trash (BR-09). It is excluded from "
                + "every balance and report while this is true, and can be brought back with the "
                + "restore endpoint (UC-10 A1).", example = "false")
        Boolean isDeleted,

        @Schema(description = "When the record was deleted. Omitted unless it is deleted.",
                example = "2026-09-25T09:30:00", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime deletedAt) {
}
