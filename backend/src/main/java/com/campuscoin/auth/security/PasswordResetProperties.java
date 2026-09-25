package com.campuscoin.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code campuscoin.security.password-reset} block of {@code application.yml}.
 *
 * @param linkBaseUrl  the Angular reset screen the link points at. The raw token is appended as
 *                     a query parameter by the service.
 * @param sinkEnabled  development only. When true, {@link FilePasswordResetNotifier} is used and
 *                     the link is written to {@code sinkFile} so a developer can complete UC-03
 *                     without a mail server. Production sets this false and no link is written to
 *                     disk. The application log is never used either way.
 * @param sinkFile     where the development sink writes. Defaults to a file under
 *                     {@code backend/target/}, which is gitignored, so a generated token cannot
 *                     be committed. Tests point it at a temporary file.
 */
@ConfigurationProperties(prefix = "campuscoin.security.password-reset")
public record PasswordResetProperties(String linkBaseUrl, Boolean sinkEnabled, String sinkFile) {
}
