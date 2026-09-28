package com.campuscoin.auth.security;

public interface PasswordResetNotifier {

    void sendPasswordResetLink(String email, String resetLink);
}
