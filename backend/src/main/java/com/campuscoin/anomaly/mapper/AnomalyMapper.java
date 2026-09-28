package com.campuscoin.anomaly.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.anomaly.dto.FlaggedTransactionResponse;
import com.campuscoin.anomaly.entity.FlaggedTransactionRow;
import com.campuscoin.common.crypto.EncryptionService;

@Component
public class AnomalyMapper {

    private final EncryptionService encryptionService;

    public AnomalyMapper(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    public List<FlaggedTransactionResponse> toResponses(List<FlaggedTransactionRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }

    public FlaggedTransactionResponse toResponse(FlaggedTransactionRow row) {
        return new FlaggedTransactionResponse(
                row.transactionId(),
                row.categoryId(),
                row.categoryName(),
                row.categoryType(),
                row.amount(),
                row.txnDate(),
                encryptionService.decryptStored(row.encryptedDescription()),
                row.isFlagged(),
                row.flagType(),
                row.flagNote());
    }
}
