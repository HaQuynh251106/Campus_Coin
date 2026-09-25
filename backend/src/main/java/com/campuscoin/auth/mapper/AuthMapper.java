package com.campuscoin.auth.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.auth.dto.UserSummaryResponse;
import com.campuscoin.auth.entity.User;

/**
 * Maps the persistence model to the API model for the authentication module.
 *
 * <p>Small enough to look trivial, and deliberately kept: it is the single place where the
 * decision "which columns may leave the server" is written down. Returning a {@link User}
 * straight from a controller would work today and leak every column added later - including
 * {@code password_hash} and {@code token_version}.
 *
 * <p>Kept as a plain class rather than pulled in from a mapping library: one mapping with five
 * fields does not justify a dependency.
 */
@Component
public class AuthMapper {

    /**
     * UC-02 B2: the account summary embedded in the sign-in response.
     *
     * <p>No password hash, no token version, no status, no session or refresh token.
     */
    public UserSummaryResponse toUserSummary(User user) {
        return new UserSummaryResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole());
    }
}
