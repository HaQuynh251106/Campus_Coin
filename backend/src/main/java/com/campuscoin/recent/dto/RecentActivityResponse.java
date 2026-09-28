package com.campuscoin.recent.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.recent.entity.RecentAction;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A transaction the student recently opened or changed (UC-26).")
public record RecentActivityResponse(

        @Schema(description = "The transaction's `id`. It is the identifier the transactions "
                + "endpoints use, so the entry can be opened directly.", example = "31")
        Long transactionId,

        @Schema(description = "What the student did: `VIEWED` opened it to read, `EDITED` changed it. "
                + "One transaction can appear twice, once per action.", example = "VIEWED")
        RecentAction action,

        @Schema(description = "When the action happened, in the application's zone. The list is "
                + "ordered by this, most recent first.", example = "2026-09-25T19:04:11")
        LocalDateTime occurredAt,

        @Schema(description = "Identifier of the category the transaction is filed under (BR-05).",
                example = "4")
        Long categoryId,

        @Schema(description = "`EXPENSE` or `INCOME`, read from the category rather than sent with "
                + "the transaction.", example = "EXPENSE")
        CategoryType categoryType,

        @Schema(description = "The transaction's amount, in the account's currency.", example = "25.00")
        BigDecimal amount,

        @Schema(description = "The student's own description of the record, decrypted. Omitted when "
                + "there is none.", example = "Campus cafe - lunch with Linh", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String description,

        @Schema(description = "The date the transaction was recorded against, which is the date the "
                + "student entered rather than the date of this action.", example = "2026-09-24")
        LocalDate txnDate) {
}
