package com.campuscoin.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;

@Schema(description = "The transaction fields to change. Omit a field to leave it as it is (UC-10).")
public record UpdateTransactionRequest(

        @Schema(description = "Move the record to another category, which also changes whether it "
                + "counts as income or expense (BR-05).", example = "2", nullable = true)
        Long categoryId,

        @Schema(description = "New amount. Strictly greater than zero.", example = "15.00",
                nullable = true)
        @DecimalMin(value = "0.00", inclusive = false,
                message = "Amount must be greater than zero.")
        @Digits(integer = 13, fraction = 2,
                message = "Amount must be at most 9,999,999,999,999.99 with two decimal places.")
        BigDecimal amount,

        @Schema(description = "New date. Cannot be in the future (BR-08).", example = "2026-09-20",
                nullable = true)
        LocalDate txnDate,

        @Schema(description = "Note. Send an empty string to clear it.",
                example = "Lunch with the study group", nullable = true)

        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Description must be at most 255 characters.")
        String description) {
}
