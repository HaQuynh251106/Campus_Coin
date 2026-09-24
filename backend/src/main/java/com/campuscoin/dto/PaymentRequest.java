package com.campuscoin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaymentRequest {

    @NotBlank(message = "Student ID is required")
    private String studentId;

    @NotBlank(message = "Merchant Code is required")
    private String merchantCode;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.50", message = "Minimum payment is 0.50 CCOIN")
    private BigDecimal amount;

    private String description;
}
