package com.campuscoin.categorisation.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.categorisation.entity.TransactionCategorisationRow;
import com.campuscoin.common.crypto.EncryptionService;

@Component
public class CategorisationMapper {

    private final EncryptionService encryptionService;

    public CategorisationMapper(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    public String description(TransactionCategorisationRow row) {
        return encryptionService.decryptStored(row.encryptedDescription());
    }
}
