package com.campuscoin.budget.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

@Schema(description = "The budget field to change (UC-13).")
public record UpdateBudgetRequest(

        @Schema(description = "New limit for the month. Strictly greater than zero, at most two "
                + "decimal places.", example = "350.00", nullable = true)
        @DecimalMin(value = "0.00", inclusive = false,
                message = "Limit amount must be greater than zero.")
        @Digits(integer = 13, fraction = 2,
                message = "Limit amount must be at most 9,999,999,999,999.99 with two decimal "
                        + "places.")
        BigDecimal limitAmount) {
}
