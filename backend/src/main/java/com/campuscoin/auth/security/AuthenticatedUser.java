package com.campuscoin.auth.security;

import java.security.Principal;

import com.campuscoin.auth.entity.UserRole;

/**
 * The identity behind the current request, derived from the verified JWT.
 *
 * <p>Controllers receive this instead of a user id from the request body or query string. BR-02
 * requires ownership to be enforced server-side, and the only trustworthy source of "who is
 * calling" is the signed token - a client-supplied {@code userId} is just an untrusted number.
 *
 * <p>{@code sessionTokenHash} is carried so logout can revoke exactly the session that made the
 * request, without the service having to re-parse the Authorization header.
 */
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
