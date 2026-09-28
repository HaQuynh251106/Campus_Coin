package com.campuscoin.transaction.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.category.entity.Category;
import com.campuscoin.common.crypto.EncryptionService;
import com.campuscoin.transaction.dto.TransactionResponse;
import com.campuscoin.transaction.entity.Transaction;

@Component
public class TransactionMapper {

    private final EncryptionService encryptionService;

    public TransactionMapper(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    public TransactionResponse toResponse(Transaction transaction) {
        Category category = transaction.getCategory();

        return new TransactionResponse(
                transaction.getId(),
                category.getId(),
                category.getName(),
                category.getIcon(),
                category.getColor(),
                category.getType(),
                transaction.getAmount(),
                transaction.getTxnDate(),
                encryptionService.decryptStored(transaction.getDescription()),
                transaction.getSource(),
                transaction.getIsDeleted(),
                transaction.getDeletedAt());
    }
}
