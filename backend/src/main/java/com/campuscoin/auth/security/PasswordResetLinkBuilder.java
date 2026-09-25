package com.campuscoin.auth.security;

import org.springframework.stereotype.Component;

/**
 * Builds the link a student follows to choose a new password (UC-03 B2, BR-04).
 *
 * <p>Extracted from {@code PasswordResetService} when the administration module gained a second
 * caller: UC-22 B4 lets an administrator send the same link, and both flows must produce a link the
 * reset screen can read. If the two built it separately, one of them could later gain a query
 * parameter, or change the separator, and the other would keep minting links that fail - a defect that
 * would show up as "the link you sent me doesn't work" rather than as a failing test.
 *
 * <p><b>The raw token is the one value that must never leave this method's caller in a form that is
 * stored.</b> The link containing it goes to the account's mailbox and nowhere else; only its SHA-256
 * hash reaches the database. Nothing here logs, and neither caller may log the result - see the
 * contract on {@link PasswordResetNotifier}.
 */
@Component
public class PasswordResetLinkBuilder {

    private final PasswordResetProperties properties;

    public PasswordResetLinkBuilder(PasswordResetProperties properties) {
        this.properties = properties;
    }

    /**
     * The full link for a freshly generated raw token.
     *
     * <p>The separator is chosen from the base URL rather than fixed, because the configured screen
     * URL may already carry a query string - a deployment could point it at a SPA route that does.
     * Appending {@code ?token=} to a URL that already has a {@code ?} would make the token part of the
     * previous parameter's value and the screen would never see it.
     *
     * <p>The raw token is appended unencoded. It is URL-safe by construction -
     * {@link TokenHashService#newSecretToken()} encodes 32 random bytes as URL-safe Base64 without
     * padding, so it contains no character that needs escaping - and encoding it here would produce a
     * parameter the verification step, which hashes what it is given, would not match.
     *
     * @param rawToken the value from {@link TokenHashService#newSecretToken()}, never a stored hash
     */
    public String build(String rawToken) {
        String baseUrl = properties.linkBaseUrl();
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "token=" + rawToken;
    }
}
