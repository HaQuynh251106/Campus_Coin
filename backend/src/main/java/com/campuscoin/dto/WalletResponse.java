package com.campuscoin.dto;

import com.campuscoin.model.enums.WalletStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletResponse {
    private Long id;
    private String studentId;
    private String ownerName;
    private String email;
    private BigDecimal balance;
    private String currency;
    private WalletStatus status;
    private LocalDateTime updatedAt;
}
