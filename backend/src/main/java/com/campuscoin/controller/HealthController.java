package com.campuscoin.controller;

import com.campuscoin.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/health")
@Tag(name = "Health Check", description = "Endpoints for server health verification")
public class HealthController {

    @GetMapping
    @Operation(summary = "Check backend service health status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkHealth() {
        return ResponseEntity.ok(ApiResponse.ok("Campus Coin Backend is running smoothly", Map.of(
                "status", "UP",
                "service", "Campus Coin Backend",
                "version", "1.0.0",
                "framework", "Spring Boot 3.4.3",
                "java", "Java 21 LTS"
        )));
    }
}
