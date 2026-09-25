package com.campuscoin.auth.controller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the client address recorded on a session and on a reset token.
 *
 * <p>Kept in one place because two controllers and three endpoints need the same value, and
 * because the alternative - reading {@code getRemoteAddr()} at each call site - would let the
 * two disagree about whether a proxy header is consulted.
 *
 * <p>{@code X-Forwarded-For} is not read. It is client-controlled unless a trusted proxy is known
 * to have set it, and this deployment has no such proxy configured; trusting it would let a
 * caller forge the address stored in the audit columns. A deployment behind a reverse proxy
 * should enable Spring's {@code ForwardedHeaderFilter} instead, which applies the same rule
 * consistently across the application.
 */
final class ClientAddress {

    private ClientAddress() {
    }

    static String of(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
