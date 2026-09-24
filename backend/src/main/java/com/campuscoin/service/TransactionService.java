package com.campuscoin.service;

import com.campuscoin.dto.PaymentRequest;
import com.campuscoin.dto.TopupRequest;
import com.campuscoin.dto.TransactionResponse;
import com.campuscoin.dto.TransferRequest;
import com.campuscoin.model.entity.Merchant;
import com.campuscoin.model.entity.Transaction;
import com.campuscoin.model.entity.Wallet;
import com.campuscoin.model.enums.TransactionStatus;
import com.campuscoin.model.enums.TransactionType;
import com.campuscoin.model.enums.WalletStatus;
import com.campuscoin.repository.MerchantRepository;
import com.campuscoin.repository.TransactionRepository;
import com.campuscoin.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;
    private final MerchantRepository merchantRepository;

    public List<TransactionResponse> getAllTransactions() {
        return transactionRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<TransactionResponse> getTransactionsByStudentId(String studentId) {
        Wallet wallet = walletRepository.findByUserStudentId(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for student ID: " + studentId));
        return transactionRepository.findAllByWallet(wallet).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public TransactionResponse transfer(TransferRequest request) {
        if (request.getSenderStudentId().equalsIgnoreCase(request.getReceiverStudentId())) {
            throw new IllegalArgumentException("Cannot transfer coins to yourself");
        }

        Wallet senderWallet = walletRepository.findByUserStudentId(request.getSenderStudentId())
                .orElseThrow(() -> new IllegalArgumentException("Sender wallet not found: " + request.getSenderStudentId()));

        Wallet receiverWallet = walletRepository.findByUserStudentId(request.getReceiverStudentId())
                .orElseThrow(() -> new IllegalArgumentException("Receiver wallet not found: " + request.getReceiverStudentId()));

        if (senderWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Sender wallet is not active");
        }
        if (receiverWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Receiver wallet is not active");
        }

        if (senderWallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new IllegalArgumentException("Insufficient balance. Current balance: " + senderWallet.getBalance());
        }

        // Deduct from sender, add to receiver
        senderWallet.setBalance(senderWallet.getBalance().subtract(request.getAmount()));
        receiverWallet.setBalance(receiverWallet.getBalance().add(request.getAmount()));

        walletRepository.save(senderWallet);
        walletRepository.save(receiverWallet);

        Transaction transaction = Transaction.builder()
                .transactionCode("TXF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .senderWallet(senderWallet)
                .receiverWallet(receiverWallet)
                .amount(request.getAmount())
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.SUCCESS)
                .description(request.getDescription() != null ? request.getDescription() : "P2P Coin Transfer")
                .build();

        return mapToResponse(transactionRepository.save(transaction));
    }

    @Transactional
    public TransactionResponse topup(TopupRequest request) {
        Wallet wallet = walletRepository.findByUserStudentId(request.getStudentId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + request.getStudentId()));

        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet is not active");
        }

        wallet.setBalance(wallet.getBalance().add(request.getAmount()));
        walletRepository.save(wallet);

        Transaction transaction = Transaction.builder()
                .transactionCode("TOP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .senderWallet(null) // System / external payment
                .receiverWallet(wallet)
                .amount(request.getAmount())
                .type(TransactionType.TOPUP)
                .status(TransactionStatus.SUCCESS)
                .description("Top-up via " + (request.getPaymentMethod() != null ? request.getPaymentMethod() : "Bank/CampusCard"))
                .build();

        return mapToResponse(transactionRepository.save(transaction));
    }

    @Transactional
    public TransactionResponse payMerchant(PaymentRequest request) {
        Wallet studentWallet = walletRepository.findByUserStudentId(request.getStudentId())
                .orElseThrow(() -> new IllegalArgumentException("Student wallet not found: " + request.getStudentId()));

        Merchant merchant = merchantRepository.findByMerchantCode(request.getMerchantCode())
                .orElseThrow(() -> new IllegalArgumentException("Merchant not found: " + request.getMerchantCode()));

        Wallet merchantWallet = merchant.getWallet();
        if (merchantWallet == null) {
            throw new IllegalStateException("Merchant has no associated wallet");
        }

        if (studentWallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new IllegalArgumentException("Insufficient balance to pay " + merchant.getName());
        }

        studentWallet.setBalance(studentWallet.getBalance().subtract(request.getAmount()));
        merchantWallet.setBalance(merchantWallet.getBalance().add(request.getAmount()));

        walletRepository.save(studentWallet);
        walletRepository.save(merchantWallet);

        Transaction transaction = Transaction.builder()
                .transactionCode("PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .senderWallet(studentWallet)
                .receiverWallet(merchantWallet)
                .amount(request.getAmount())
                .type(TransactionType.PURCHASE)
                .status(TransactionStatus.SUCCESS)
                .description(request.getDescription() != null ? request.getDescription() : "Payment at " + merchant.getName())
                .build();

        return mapToResponse(transactionRepository.save(transaction));
    }

    public TransactionResponse mapToResponse(Transaction tx) {
        String senderName = tx.getSenderWallet() != null && tx.getSenderWallet().getUser() != null
                ? tx.getSenderWallet().getUser().getFullName() : "SYSTEM";
        String senderStudentId = tx.getSenderWallet() != null && tx.getSenderWallet().getUser() != null
                ? tx.getSenderWallet().getUser().getStudentId() : "SYSTEM";

        String receiverName = tx.getReceiverWallet() != null && tx.getReceiverWallet().getUser() != null
                ? tx.getReceiverWallet().getUser().getFullName() : "SYSTEM";
        String receiverStudentId = tx.getReceiverWallet() != null && tx.getReceiverWallet().getUser() != null
                ? tx.getReceiverWallet().getUser().getStudentId() : "SYSTEM";

        return TransactionResponse.builder()
                .id(tx.getId())
                .transactionCode(tx.getTransactionCode())
                .senderName(senderName)
                .senderStudentId(senderStudentId)
                .receiverName(receiverName)
                .receiverStudentId(receiverStudentId)
                .amount(tx.getAmount())
                .type(tx.getType())
                .status(tx.getStatus())
                .description(tx.getDescription())
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
