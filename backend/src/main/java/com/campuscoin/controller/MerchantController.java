package com.campuscoin.controller;

import com.campuscoin.dto.ApiResponse;
import com.campuscoin.model.entity.Merchant;
import com.campuscoin.service.MerchantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/merchants")
@RequiredArgsConstructor
@Tag(name = "Merchants & Campus Stores", description = "Endpoints for campus canteens, bookstores, printing shops")
public class MerchantController {

    private final MerchantService merchantService;

    @GetMapping
    @Operation(summary = "Get list of all campus stores and merchants")
    public ResponseEntity<ApiResponse<List<Merchant>>> getAllMerchants() {
        return ResponseEntity.ok(ApiResponse.ok(merchantService.getAllMerchants()));
    }

    @GetMapping("/{code}")
    @Operation(summary = "Get merchant details by Merchant Code")
    public ResponseEntity<ApiResponse<Merchant>> getMerchantByCode(@PathVariable String code) {
        return merchantService.getMerchantByCode(code)
                .map(m -> ResponseEntity.ok(ApiResponse.ok(m)))
                .orElse(ResponseEntity.notFound().build());
    }
}
