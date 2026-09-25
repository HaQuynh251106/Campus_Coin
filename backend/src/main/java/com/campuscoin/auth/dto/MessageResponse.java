package com.campuscoin.auth.dto;

/**
 * A single human-readable message, used where a use case defines the exact wording.
 *
 * <p>Currently the UC-03 password-reset request and completion responses, both of which must be
 * text the requirement fixes rather than a payload the client interprets.
 */
public record MessageResponse(String message) {
}
