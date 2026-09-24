package com.campuscoin.service;

import com.campuscoin.model.entity.Merchant;
import com.campuscoin.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MerchantService {

    private final MerchantRepository merchantRepository;

    public List<Merchant> getAllMerchants() {
        return merchantRepository.findAll();
    }

    public Optional<Merchant> getMerchantByCode(String code) {
        return merchantRepository.findByMerchantCode(code);
    }
}
