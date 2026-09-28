package com.campuscoin.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoopPasswordResetNotifier implements PasswordResetNotifier {

    private static final Logger log = LoggerFactory.getLogger(NoopPasswordResetNotifier.class);

    @Override
    public void sendPasswordResetLink(String email, String resetLink) {
        log.warn("No password reset delivery channel is configured; the reset link was not sent. "
                + "Configure a PasswordResetNotifier implementation before using password reset "
                + "in this environment.");
    }
}
