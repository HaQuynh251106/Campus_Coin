package com.campuscoin.controller;

import com.campuscoin.dto.ApiResponse;
import com.campuscoin.dto.WalletResponse;
import com.campuscoin.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
@Tag(name = "Wallet Management", description = "Endpoints for managing student coin wallets and balances")
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    @Operation(summary = "Get all campus wallets")
    public ResponseEntity<ApiResponse<List<WalletResponse>>> getAllWallets() {
        return ResponseEntity.ok(ApiResponse.ok(walletService.getAllWallets()));
    }

    @GetMapping("/{studentId}")
    @Operation(summary = "Get wallet details and balance by Student ID")
    public ResponseEntity<ApiResponse<WalletResponse>> getWalletByStudentId(@PathVariable String studentId) {
        return walletService.getWalletByStudentId(studentId)
                .map(w -> ResponseEntity.ok(ApiResponse.ok(w)))
                .orElse(ResponseEntity.notFound().build());
    }
}
