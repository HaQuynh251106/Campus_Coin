package com.campuscoin.service;

import com.campuscoin.dto.WalletResponse;
import com.campuscoin.model.entity.User;
import com.campuscoin.model.entity.Wallet;
import com.campuscoin.model.enums.WalletStatus;
import com.campuscoin.repository.UserRepository;
import com.campuscoin.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    public List<WalletResponse> getAllWallets() {
        return walletRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public Optional<WalletResponse> getWalletByStudentId(String studentId) {
        return walletRepository.findByUserStudentId(studentId)
                .map(this::mapToResponse);
    }

    public Wallet getWalletEntityByStudentId(String studentId) {
        return walletRepository.findByUserStudentId(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for student ID: " + studentId));
    }

    public WalletResponse mapToResponse(Wallet wallet) {
        User user = wallet.getUser();
        return WalletResponse.builder()
                .id(wallet.getId())
                .studentId(user != null ? user.getStudentId() : null)
                .ownerName(user != null ? user.getFullName() : null)
                .email(user != null ? user.getEmail() : null)
                .balance(wallet.getBalance())
                .currency(wallet.getCurrency())
                .status(wallet.getStatus())
                .updatedAt(wallet.getUpdatedAt())
                .build();
    }
}
