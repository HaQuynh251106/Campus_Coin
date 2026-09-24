package com.campuscoin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TopupRequest {

    @NotBlank(message = "Student ID is required")
    private String studentId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "10.00", message = "Minimum top-up amount is 10 CCOIN")
    private BigDecimal amount;

    private String paymentMethod; // e.g., VNPay, MoMo, BankTransfer, CampusCard
}
