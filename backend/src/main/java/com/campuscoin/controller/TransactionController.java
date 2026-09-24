package com.campuscoin.controller;

import com.campuscoin.dto.*;
import com.campuscoin.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Endpoints for coin transfers, top-ups, and merchant payments")
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping
    @Operation(summary = "Get transaction history across campus")
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> getAllTransactions() {
        return ResponseEntity.ok(ApiResponse.ok(transactionService.getAllTransactions()));
    }

    @GetMapping("/student/{studentId}")
    @Operation(summary = "Get transaction history for a specific student")
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> getStudentTransactions(@PathVariable String studentId) {
        return ResponseEntity.ok(ApiResponse.ok(transactionService.getTransactionsByStudentId(studentId)));
    }

    @PostMapping("/transfer")
    @Operation(summary = "P2P Coin Transfer between students or faculty")
    public ResponseEntity<ApiResponse<TransactionResponse>> transferCoins(@Valid @RequestBody TransferRequest request) {
        try {
            TransactionResponse response = transactionService.transfer(request);
            return ResponseEntity.ok(ApiResponse.ok("Transfer completed successfully", response));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/topup")
    @Operation(summary = "Deposit / Top-up coins into a student wallet")
    public ResponseEntity<ApiResponse<TransactionResponse>> topupCoins(@Valid @RequestBody TopupRequest request) {
        try {
            TransactionResponse response = transactionService.topup(request);
            return ResponseEntity.ok(ApiResponse.ok("Top-up completed successfully", response));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/pay")
    @Operation(summary = "Pay a campus merchant (canteen, library, bookstore, etc.)")
    public ResponseEntity<ApiResponse<TransactionResponse>> payMerchant(@Valid @RequestBody PaymentRequest request) {
        try {
            TransactionResponse response = transactionService.payMerchant(request);
            return ResponseEntity.ok(ApiResponse.ok("Payment to merchant completed successfully", response));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
