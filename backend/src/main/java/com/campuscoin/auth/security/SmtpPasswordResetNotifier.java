package com.campuscoin.auth.security;

import java.io.UnsupportedEncodingException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

public class SmtpPasswordResetNotifier implements PasswordResetNotifier {

    private static final Logger log = LoggerFactory.getLogger(SmtpPasswordResetNotifier.class);

    private final JavaMailSender mailSender;
    private final PasswordResetProperties properties;

    public SmtpPasswordResetNotifier(JavaMailSender mailSender, PasswordResetProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendPasswordResetLink(String email, String resetLink) {
        try {
            MimeMessage message = mailSender.createMimeMessage();

            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(properties.fromAddress(), properties.effectiveFromName());
            helper.setTo(email);
            helper.setSubject("Reset your Campus Coin password");
            helper.setText(plainText(resetLink), html(resetLink));

            mailSender.send(message);

            log.info("Password reset link sent through SMTP");

        } catch (MailException | MessagingException | UnsupportedEncodingException ex) {
            log.warn("Could not send the password reset link. The request still succeeds so the "
                    + "response stays identical for every address.", ex);
        }
    }

    private static String plainText(String resetLink) {
        return """
                You asked to reset your Campus Coin password.

                Open this link to choose a new one:
                %s

                The link can be used once and expires soon. If you did not ask for a reset, you can
                ignore this message - your password has not changed.
                """.formatted(resetLink);
    }

    private static String html(String resetLink) {
        return """
                <p>You asked to reset your Campus Coin password.</p>
                <p><a href="%1$s">Choose a new password</a></p>
                <p>Or paste this into your browser:<br>%1$s</p>
                <p>The link can be used once and expires soon. If you did not ask for a reset, you
                can ignore this message &mdash; your password has not changed.</p>
                """.formatted(resetLink);
    }
}
