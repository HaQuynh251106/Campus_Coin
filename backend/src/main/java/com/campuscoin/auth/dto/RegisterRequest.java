package com.campuscoin.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/register} (UC-01 B1).
 *
 * <p>Four fields, exactly as the use case lists them: full name, email, password and password
 * confirmation. No role, status or currency field is accepted - UC-01's postcondition fixes all
 * three (STUDENT, ACTIVE, the system default currency), and letting a client send them would be
 * a privilege-escalation route rather than a convenience.
 *
 * <p>The constraints here cover UC-01 B3's format and strength checks, so a malformed request is
 * rejected on the way in with a per-field error (UC-01 A2). They do not replace the database's
 * own rules: the unique email is still enforced by {@code uk_users_email}, and the service still
 * handles the duplicate case (UC-01 A1).
 *
 * <p>Password rules: 8 to 72 characters, at least one upper-case letter, one lower-case letter
 * and one digit. The upper bound of 72 is not arbitrary - bcrypt ignores everything past 72
 * bytes, so accepting a longer password would mean silently ignoring part of it.
 *
 * <p>Field names are lowerCamelCase and match the documented API contract exactly; Angular sends
 * this shape as-is.
 */
public record RegisterRequest(

        @NotBlank(message = "Full name is required.")
        @Size(min = 2, max = 120, message = "Full name must be between 2 and 120 characters.")
        String fullName,

        @NotBlank(message = "Email is required.")
        @Email(message = "Email must be a well-formed email address.")
        @Size(max = 190, message = "Email must be at most 190 characters.")
        String email,

        @NotBlank(message = "Password is required.")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters.")
        @Pattern(regexp = ".*[A-Z].*", message = "Password must contain at least one upper-case letter.")
        @Pattern(regexp = ".*[a-z].*", message = "Password must contain at least one lower-case letter.")
        @Pattern(regexp = ".*\\d.*", message = "Password must contain at least one digit.")
        String password,

        @NotBlank(message = "Password confirmation is required.")
        String confirmPassword) {
}
