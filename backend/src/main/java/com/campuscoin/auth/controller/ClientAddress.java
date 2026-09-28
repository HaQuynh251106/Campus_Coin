package com.campuscoin.auth.controller;

import jakarta.servlet.http.HttpServletRequest;

final class ClientAddress {

    private ClientAddress() {
    }

    static String of(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
