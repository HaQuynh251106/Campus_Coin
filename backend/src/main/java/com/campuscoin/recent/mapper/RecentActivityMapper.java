package com.campuscoin.recent.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.common.crypto.EncryptionService;
import com.campuscoin.recent.dto.RecentActivityResponse;
import com.campuscoin.recent.entity.RecentActivityRow;

@Component
public class RecentActivityMapper {

    private final EncryptionService encryptionService;

    public RecentActivityMapper(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    public List<RecentActivityResponse> toResponses(List<RecentActivityRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }

    public RecentActivityResponse toResponse(RecentActivityRow row) {
        return new RecentActivityResponse(
                row.transactionId(),
                row.action(),
                row.occurredAt(),
                row.categoryId(),
                row.categoryType(),
                row.amount(),
                encryptionService.decryptStored(row.encryptedDescription()),
                row.txnDate());
    }
}
