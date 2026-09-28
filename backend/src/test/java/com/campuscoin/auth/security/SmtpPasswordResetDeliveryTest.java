package com.campuscoin.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.campuscoin.auth.security.PasswordResetProperties.Smtp;

class SmtpPasswordResetDeliveryTest {

    private static final String EMAIL = "student@campus.example";
    private static final String FROM = "no-reply@campus.example";

    private static final String RESET_LINK =
            "http://localhost:4200/reset-password?token=abc123-XYZ_-456";

    private static final PasswordResetProperties PROPERTIES = new PasswordResetProperties(
            "http://localhost:4200/reset-password", false, null, FROM, "Campus Coin", null);

    @Test
    @DisplayName("UC-03: the link is delivered over SMTP to the requested account")
    void theLinkIsDeliveredOverSmtp() throws Exception {
        try (SmtpStub server = new SmtpStub()) {
            JavaMailSenderImpl sender = (JavaMailSenderImpl) PasswordResetConfig.mailSender(
                    new Smtp("127.0.0.1", server.port(), null, null, false, false));

            new SmtpPasswordResetNotifier(sender, PROPERTIES).sendPasswordResetLink(EMAIL, RESET_LINK);

            String raw = server.awaitMessage();

            assertThat(server.envelopeFrom()).contains("<" + FROM + ">");
            assertThat(server.envelopeTo()).contains("<" + EMAIL + ">");

            MimeMessage message = new MimeMessage(
                    Session.getInstance(new Properties()),
                    new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));

            assertThat(message.getSubject()).isEqualTo("Reset your Campus Coin password");
            assertThat(message.getFrom()[0].toString()).isEqualTo("Campus Coin <" + FROM + ">");
            assertThat(message.getAllRecipients()[0].toString()).isEqualTo(EMAIL);

            List<String> parts = textPartsOf(message);
            assertThat(parts).as("a plain-text part and an HTML part").hasSize(2);

            assertThat(parts).allSatisfy(part -> assertThat(part).contains(RESET_LINK));
        }
    }

    private static List<String> textPartsOf(MimeMessage message) throws Exception {
        List<String> parts = new ArrayList<>();
        collect(message.getContent(), parts);
        return parts;
    }

    private static void collect(Object content, List<String> into) throws Exception {
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                collect(part.getContent(), into);
            }
        } else if (content instanceof String text) {
            into.add(text);
        }
    }

    private static final class SmtpStub implements AutoCloseable {

        private final ServerSocket server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final CompletableFuture<String> message = new CompletableFuture<>();
        private final StringBuilder envelopeFrom = new StringBuilder();
        private final StringBuilder envelopeTo = new StringBuilder();

        SmtpStub() throws IOException {
            this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            executor.submit(this::serve);
        }

        int port() {
            return server.getLocalPort();
        }

        String envelopeFrom() {
            return envelopeFrom.toString();
        }

        String envelopeTo() {
            return envelopeTo.toString();
        }

        String awaitMessage() throws Exception {
            return message.get(15, TimeUnit.SECONDS);
        }

        private void serve() {
            try (Socket socket = server.accept();
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                 BufferedWriter out = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))) {

                reply(out, "220 smtp-stub ESMTP ready");

                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("EHLO") || line.startsWith("HELO")) {
                        reply(out, "250 smtp-stub");
                    } else if (line.startsWith("MAIL FROM")) {
                        envelopeFrom.append(line);
                        reply(out, "250 2.1.0 Sender OK");
                    } else if (line.startsWith("RCPT TO")) {
                        envelopeTo.append(line);
                        reply(out, "250 2.1.5 Recipient OK");
                    } else if (line.equals("DATA")) {
                        reply(out, "354 End data with <CR><LF>.<CR><LF>");
                        message.complete(readData(in));
                        reply(out, "250 2.0.0 Message accepted");
                    } else if (line.equals("QUIT")) {
                        reply(out, "221 2.0.0 Bye");
                        return;
                    } else {

                        reply(out, "250 2.0.0 OK");
                    }
                }
            } catch (IOException ex) {

                message.completeExceptionally(ex);
            }
        }

        private static String readData(BufferedReader in) throws IOException {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals(".")) {
                    break;
                }
                body.append(line).append("\r\n");
            }
            return body.toString();
        }

        private static void reply(BufferedWriter out, String line) throws IOException {
            out.write(line);
            out.write("\r\n");
            out.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
            executor.shutdownNow();
        }
    }
}
