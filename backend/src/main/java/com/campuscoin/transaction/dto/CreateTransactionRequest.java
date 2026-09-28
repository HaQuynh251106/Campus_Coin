package com.campuscoin.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "A transaction to record (UC-07).")
public record CreateTransactionRequest(

        @Schema(description = "The category to file this under. It decides whether the record is "
                + "income or expense (BR-05), and must be one of your own categories or a shared "
                + "default one.", example = "1")
        @NotNull(message = "Category is required.")
        Long categoryId,

        @Schema(description = "How much moved. Strictly greater than zero, at most two decimal "
                + "places.", example = "12.50")
        @NotNull(message = "Amount is required.")

        @DecimalMin(value = "0.00", inclusive = false,
                message = "Amount must be greater than zero.")

        @Digits(integer = 13, fraction = 2,
                message = "Amount must be at most 9,999,999,999,999.99 with two decimal places.")
        BigDecimal amount,

        @Schema(description = "The date the money moved. Cannot be in the future (BR-08).",
                example = "2026-09-24")
        @NotNull(message = "Date is required.")
        LocalDate txnDate,

        @Schema(description = "Optional note. An empty string stores no description.",
                example = "Lunch with the study group", nullable = true)

        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Description must be at most 255 characters.")
        String description) {
}
