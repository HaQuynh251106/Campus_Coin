package com.campuscoin.admin.controller;

import jakarta.servlet.http.HttpServletRequest;

final class AdminRequestContext {

    private AdminRequestContext() {
    }

    static String clientAddress(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
