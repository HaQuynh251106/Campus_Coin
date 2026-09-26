package com.campuscoin.auth.security;

import java.nio.file.Path;
import java.util.Properties;

import com.campuscoin.auth.security.PasswordResetProperties.Smtp;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Chooses how a password reset link is delivered.
 *
 * <p>A conditional bean rather than an {@code if} inside the service, so the service depends only
 * on the {@link PasswordResetNotifier} port and the choice of transport stays a deployment
 * concern. The transport is chosen from what the deployment actually has, in this order:
 *
 * <ol>
 *   <li><b>The development sink</b>, when {@code sink-enabled} is true. It writes the link to a file
 *       under {@code backend/target/} - which is already gitignored, so a generated token can never
 *       be committed - and lets UC-03 be demonstrated without a mail server. It is deliberately
 *       checked before SMTP so a developer with mail credentials can still use the sink by setting
 *       the flag, which is the point of the flag.</li>
 *   <li><b>SMTP</b>, when {@code MAIL_HOST} and {@code MAIL_FROM_ADDRESS} are both set. See
 *       {@link #mailSender} for why this class builds the sender rather than injecting Spring
 *       Boot's.</li>
 *   <li><b>Nothing</b>, otherwise - the {@link NoopPasswordResetNotifier}, which the request treats
 *       as a success so the response stays identical for every address (UC-03 B3).</li>
 * </ol>
 *
 * <p><b>The mail sender is built here, not injected.</b> Spring Boot's
 * {@code MailSenderAutoConfiguration} creates a {@code JavaMailSender} when the property
 * {@code spring.mail.host} is <em>present</em>, and "present" includes an empty string - so the
 * conventional {@code spring.mail.host: ${MAIL_HOST:}} would construct a sender pointed at a blank
 * host in every deployment that has no mail server, which is precisely the deployment the no-op
 * exists to serve. Owning the connection lets one test - {@link Smtp#isConfigured()} - decide, and
 * it treats a blank host as "no mail server" the way {@code AiProperties} treats a blank API key.
 * The sender is not exposed as a bean either, because nothing else in the application mails: a bean
 * would be a second, separately-reachable mail sender that a future contributor could use without
 * going through the one place the security rules for a reset link are stated.
 */
@Configuration
public class PasswordResetConfig {

    /**
     * Bound on how long a send may block, per phase. The request that asks for a reset waits for
     * the SMTP conversation, so an unreachable mail server must fail promptly rather than hold the
     * HTTP worker until the operating system gives up - which can otherwise be minutes. Ten seconds
     * is long enough for a real provider on a slow link and short enough that a dead one degrades
     * one request instead of stalling every worker.
     */
    private static final String CONNECT_TIMEOUT_MS = "10000";
    private static final String READ_TIMEOUT_MS = "10000";
    private static final String WRITE_TIMEOUT_MS = "10000";

    @Bean
    @ConditionalOnMissingBean(PasswordResetNotifier.class)
    public PasswordResetNotifier passwordResetNotifier(PasswordResetProperties properties) {
        if (Boolean.TRUE.equals(properties.sinkEnabled())) {
            String configured = properties.sinkFile();
            Path sinkFile = configured == null || configured.isBlank()
                    ? Path.of("target", "password-reset-dev.log")
                    : Path.of(configured);
            return new FilePasswordResetNotifier(sinkFile);
        }

        Smtp smtp = properties.smtp();
        if (smtp != null && smtp.isConfigured() && properties.hasFromAddress()) {
            return new SmtpPasswordResetNotifier(mailSender(smtp), properties);
        }

        return new NoopPasswordResetNotifier();
    }

    /**
     * Builds the SMTP connection from the {@code MAIL_*} variables.
     *
     * <p>Package-private and static so a unit test can assert what was configured - the host, the
     * port, whether STARTTLS is on - without a mail server. The password is set on the sender and
     * read by the mail library when it authenticates; it is never logged here.
     *
     * <p>STARTTLS defaults on. The reset link carries a one-time token in its query string, so a
     * connection that a network observer can read is a connection that leaks account access; a
     * deployment that needs it off (a local catcher with no TLS) says so explicitly with
     * {@code MAIL_STARTTLS=false}. Implicit TLS is the separate {@code MAIL_SSL} switch for port
     * 465, which is why the two are not inferred from the port number.
     */
    static JavaMailSender mailSender(Smtp smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.effectivePort());
        sender.setDefaultEncoding("UTF-8");

        Properties mail = sender.getJavaMailProperties();
        mail.put("mail.smtp.auth", Boolean.toString(smtp.hasCredentials()));
        mail.put("mail.smtp.starttls.enable", Boolean.toString(smtp.useStartTls()));
        mail.put("mail.smtp.ssl.enable", Boolean.toString(smtp.useSsl()));
        mail.put("mail.smtp.connectiontimeout", CONNECT_TIMEOUT_MS);
        mail.put("mail.smtp.timeout", READ_TIMEOUT_MS);
        mail.put("mail.smtp.writetimeout", WRITE_TIMEOUT_MS);

        if (smtp.hasCredentials()) {
            sender.setUsername(smtp.username());
            sender.setPassword(smtp.password());
        }

        return sender;
    }
}
