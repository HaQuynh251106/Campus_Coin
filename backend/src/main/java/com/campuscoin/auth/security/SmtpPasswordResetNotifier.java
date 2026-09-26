package com.campuscoin.auth.security;

import java.io.UnsupportedEncodingException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Sends the UC-03 password reset link through the configured mail server.
 *
 * <p><b>The link is the only thing this class carries, and it is the raw token's only exit from the
 * application.</b> It is put in the message body and transmitted; it is never logged, never stored,
 * and never returned in an HTTP response. The tokens that reach the database are SHA-256 hashes - see
 * {@link PasswordResetLinkBuilder} - so a mailbox is the one place the usable value exists.
 *
 * <p><b>A send failure is logged and swallowed, never thrown.</b> UC-03 B3 requires the same generic
 * answer whether or not the address exists, and a delivery failure that surfaced as an error response
 * would distinguish a registered address from an unregistered one - the enumeration the requirement
 * forbids. So this method returns normally and the caller answers as it always does. The failure is
 * still logged at WARN, because an operator whose mail server is unreachable needs to know; the log
 * line names neither the address (which address exists is itself the secret) nor anything from the
 * message.
 *
 * <p>A multipart "text and HTML" message is built so a plain-text client still shows a usable link.
 * The HTML part contains only values this application produced - the fixed copy and the link - so
 * there is no student-supplied text to escape; the description of an import, for instance, is nowhere
 * near this message.
 */
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
            // true = multipart. The helper handles the encoding of the subject and the body, so a
            // reset link containing URL-safe Base64 is transmitted exactly as the screen expects it.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(properties.fromAddress(), properties.effectiveFromName());
            helper.setTo(email);
            helper.setSubject("Reset your Campus Coin password");
            helper.setText(plainText(resetLink), html(resetLink));

            mailSender.send(message);
            // Deliberately says only that a link went out. The address is not logged even here: it
            // is the fact the enumeration guard exists to protect.
            log.info("Password reset link sent through SMTP");
        // UnsupportedEncodingException comes from MimeMessageHelper's declared constructor even
        // though UTF-8 always exists; it is caught with the rest rather than rethrown, because any
        // failure on this path must leave the caller's response untouched.
        } catch (MailException | MessagingException | UnsupportedEncodingException ex) {
            log.warn("Could not send the password reset link. The request still succeeds so the "
                    + "response stays identical for every address.", ex);
        }
    }

    /** The part a text-only client reads. Kept short and free of markup. */
    private static String plainText(String resetLink) {
        return """
                You asked to reset your Campus Coin password.

                Open this link to choose a new one:
                %s

                The link can be used once and expires soon. If you did not ask for a reset, you can
                ignore this message - your password has not changed.
                """.formatted(resetLink);
    }

    /**
     * The part a normal mail client renders.
     *
     * <p>The link is placed both in the anchor's {@code href} and in the visible text, so a reader
     * who hovers before clicking can see where it goes, and one whose client strips links can copy
     * it. The only interpolated value is the link this application built; nothing here comes from a
     * request body, so there is nothing to escape for HTML.
     */
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
