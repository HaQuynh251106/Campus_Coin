package com.campuscoin.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code campuscoin.security.password-reset} block of {@code application.yml}.
 *
 * <p>Everything a reset email needs is here: where the link points, who it is sent from, and how to
 * reach the mail server. Keeping the SMTP connection in this block rather than in Spring Boot's
 * {@code spring.mail} block is deliberate - see {@link #smtp} - so that one class answers "is reset
 * email configured, and with what" and no reader has to know which of two property trees wins.
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
 * @param fromAddress  the verified sender address a reset email is sent from, from
 *                     {@code MAIL_FROM_ADDRESS}. It is the address the receiving server sees in
 *                     the envelope and the {@code From} header, so it must be one the SMTP
 *                     account is permitted to send as - otherwise a provider rejects the message
 *                     as spoofing. Blank disables SMTP delivery, because an email with no usable
 *                     sender cannot be sent.
 * @param fromName     the display name beside {@code fromAddress}, from {@code MAIL_FROM_NAME}.
 *                     Purely cosmetic; blank falls back to a fixed product name.
 * @param smtp         the mail server connection, from the {@code MAIL_*} variables. See
 *                     {@link Smtp}.
 */
@ConfigurationProperties(prefix = "campuscoin.security.password-reset")
public record PasswordResetProperties(String linkBaseUrl,
                                      Boolean sinkEnabled,
                                      String sinkFile,
                                      String fromAddress,
                                      String fromName,
                                      Smtp smtp) {

    /** The display name used when {@code MAIL_FROM_NAME} is unset. */
    public static final String DEFAULT_FROM_NAME = "Campus Coin";

    /**
     * The SMTP connection used to deliver a reset link.
     *
     * <p><b>These are this application's own properties, not Spring Boot's {@code spring.mail}
     * block.</b> The auto-configured sender is created by a {@code @ConditionalOnProperty} test on
     * {@code spring.mail.host}, and that test is satisfied by an empty string as readily as by a real
     * host - so declaring {@code host: ${MAIL_HOST:}} in a configuration file would create a mail
     * sender pointed at an empty host in every deployment that has no mail server. Owning the four
     * values here removes the question: {@link #isConfigured()} is the single test, and it treats a
     * blank host as "no mail server" the way {@code AiProperties} treats a blank API key.
     *
     * @param host     the SMTP server host, from {@code MAIL_HOST}. Blank means no mail server.
     * @param port     the SMTP port, from {@code MAIL_PORT}. Defaults to 587, the submission port.
     * @param username the SMTP account, from {@code MAIL_USERNAME}. Blank means no authentication,
     *                 which is the ordinary case for a local catcher (MailHog, Mailpit) and for some
     *                 relays that identify the caller by IP.
     * @param password the SMTP secret, from {@code MAIL_PASSWORD}. It lives in the environment only:
     *                 never in a configuration file, never in git, never in MySQL, never in a JWT,
     *                 never sent to the frontend. It is not logged, not even when a send fails.
     * @param starttls whether to upgrade the connection with STARTTLS. Defaults to true, because the
     *                 reset link carries a one-time token in its query string and sending it in the
     *                 clear would put that token on the wire. Set false for a local catcher with no
     *                 TLS; leave true for anything reaching a real provider.
     * @param ssl      whether to connect with implicit TLS. Defaults to false, which is correct for
     *                 STARTTLS on 587 and must be set true alongside port 465.
     */
    public record Smtp(String host, Integer port, String username, String password,
                       Boolean starttls, Boolean ssl) {

        public static final int DEFAULT_PORT = 587;

        /** True when a host is present, which is what decides whether a sender is built at all. */
        public boolean isConfigured() {
            return host != null && !host.isBlank();
        }

        public int effectivePort() {
            return port == null || port <= 0 ? DEFAULT_PORT : port;
        }

        public boolean useStartTls() {
            return !Boolean.FALSE.equals(starttls);
        }

        public boolean useSsl() {
            return Boolean.TRUE.equals(ssl);
        }

        public boolean hasCredentials() {
            return username != null && !username.isBlank();
        }
    }

    /** True when a sender address is present, which is half of what SMTP delivery needs. */
    public boolean hasFromAddress() {
        return fromAddress != null && !fromAddress.isBlank();
    }

    public String effectiveFromName() {
        return fromName == null || fromName.isBlank() ? DEFAULT_FROM_NAME : fromName.trim();
    }
}
