package com.campuscoin.controller;

import com.campuscoin.dto.ApiResponse;
import com.campuscoin.model.entity.User;
import com.campuscoin.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "User Management", description = "Endpoints for students, faculty and staff")
public class UserController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "Get list of all users")
    public ResponseEntity<ApiResponse<List<User>>> getAllUsers() {
        return ResponseEntity.ok(ApiResponse.ok(userService.getAllUsers()));
    }

    @GetMapping("/{studentId}")
    @Operation(summary = "Find user by Student/Staff ID")
    public ResponseEntity<ApiResponse<User>> getUserByStudentId(@PathVariable String studentId) {
        return userService.getUserByStudentId(studentId)
                .map(u -> ResponseEntity.ok(ApiResponse.ok(u)))
                .orElse(ResponseEntity.notFound().build());
    }
}
