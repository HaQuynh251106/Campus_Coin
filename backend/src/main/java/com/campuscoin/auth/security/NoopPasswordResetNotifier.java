package com.campuscoin.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production stand-in used until an email provider is configured.
 *
 * <p>It accepts the link and discards it. That is the correct behaviour for a deployment without
 * a mail provider: the alternative would be writing reset tokens to disk on a server, or logging
 * them, and both are worse than a reset that quietly does not arrive. The request still returns
 * the UC-03 B3 message, so an unknown address and an existing one remain indistinguishable.
 *
 * <p>The discarded link is not logged - not even at debug - so that enabling debug logging on a
 * server can never expose a token.
 */
public class NoopPasswordResetNotifier implements PasswordResetNotifier {

    private static final Logger log = LoggerFactory.getLogger(NoopPasswordResetNotifier.class);

    @Override
    public void sendPasswordResetLink(String email, String resetLink) {
        log.warn("No password reset delivery channel is configured; the reset link was not sent. "
                + "Configure a PasswordResetNotifier implementation before using password reset "
                + "in this environment.");
    }
}
