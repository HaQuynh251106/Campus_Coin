package com.campuscoin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TransferRequest {

    @NotBlank(message = "Sender student ID is required")
    private String senderStudentId;

    @NotBlank(message = "Receiver student ID is required")
    private String receiverStudentId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "1.00", message = "Minimum transfer amount is 1 CCOIN")
    private BigDecimal amount;

    private String description;
}
