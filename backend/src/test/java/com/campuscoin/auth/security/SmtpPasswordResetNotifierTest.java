package com.campuscoin.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * The one thing that puts the raw reset token on the wire, tested without a mail server.
 *
 * <p><b>Why this is a unit test.</b> Everything interesting here is a decision about behaviour when
 * sending goes wrong, and a real SMTP failure is slow (the timeouts are seconds) and needs a server
 * that can be made to fail on cue. A mocked {@link JavaMailSender} puts each failure at the exact
 * call under test.
 *
 * <p><b>The assertion that matters is an absence.</b> UC-03 B3 requires the reset request to answer
 * identically whether the address exists or the mail server is down, so a send failure must never
 * escape as an exception - if it did, the caller's error handling would turn a delivery fault into a
 * different response, and the difference would itself reveal which addresses are real. The first
 * two tests therefore assert the method <em>returns</em>, not that it logged or swallowed quietly.
 *
 * <p>What is sent is asserted too: the body carries the reset link, and the message goes to the
 * requested address. Those are the two facts a caller relies on; anything beyond them is the mail
 * library's behaviour, not this class's.
 */
class SmtpPasswordResetNotifierTest {

    private static final String RESET_LINK = "http://localhost:4200/reset-password?token=abc123";
    private static final String EMAIL = "student@campus.example";

    private final PasswordResetProperties properties = new PasswordResetProperties(
            "http://localhost:4200/reset-password", false, null,
            "no-reply@campus.example", "Campus Coin", null);

    private static MimeMessage message() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Test
    @DisplayName("UC-03 B3: a send failure does not escape the notifier")
    void aSendFailureIsSwallowed() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(message());
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(MimeMessage.class));

        SmtpPasswordResetNotifier notifier = new SmtpPasswordResetNotifier(mailSender, properties);

        assertThatCode(() -> notifier.sendPasswordResetLink(EMAIL, RESET_LINK))
                .doesNotThrowAnyException();
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("A failure while building the message is swallowed too")
    void aBuildFailureIsSwallowed() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        // createMimeMessage is the first call, so a throw here means the failure happens before any
        // send is attempted - a different path into the same catch, and the one a broken mail
        // library would take.
        when(mailSender.createMimeMessage()).thenThrow(new MailSendException("no session"));

        SmtpPasswordResetNotifier notifier = new SmtpPasswordResetNotifier(mailSender, properties);

        assertThatCode(() -> notifier.sendPasswordResetLink(EMAIL, RESET_LINK))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The reset link reaches the message, addressed to the requested account")
    void theLinkAndRecipientReachTheMessage() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage built = message();
        when(mailSender.createMimeMessage()).thenReturn(built);

        SmtpPasswordResetNotifier notifier = new SmtpPasswordResetNotifier(mailSender, properties);
        notifier.sendPasswordResetLink(EMAIL, RESET_LINK);

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());

        MimeMessage message = sent.getValue();
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(EMAIL);
        assertThat(message.getSubject()).isNotBlank();
        // saveChanges has already run inside MimeMessageHelper, so the parts are readable.
        assertThat(contentOf(message)).contains(RESET_LINK);
        assertThat(contentOf(message)).contains("no-reply@campus.example");
    }

    /** The whole serialized message, which is what a receiving server sees. */
    private static String contentOf(MimeMessage message) throws Exception {
        var buffer = new java.io.ByteArrayOutputStream();
        message.writeTo(buffer);
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
