package com.campuscoin.auth.security;

import java.security.Principal;

import com.campuscoin.auth.entity.UserRole;

public record AuthenticatedUser(
        Long userId,
        String email,
        String fullName,
        UserRole role,
        String sessionTokenHash) implements Principal {

    @Override
    public String getName() {
        return email;
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }
}
