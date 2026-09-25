package com.campuscoin.auth.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development stand-in for the mail provider: appends the reset link to a file instead of
 * sending email.
 *
 * <p>UC-03 cannot be demonstrated without receiving the link, and this project has no mail
 * provider. Writing to a file under the build directory keeps the flow testable end to end while
 * still honouring section 7.6 - the token goes into a gitignored artefact, never into a log line,
 * where it would be shipped to whatever collects the application's logs.
 *
 * <p>Only active when {@code campuscoin.security.password-reset.sink-enabled} is true, which is
 * the {@code dev} profile. Production uses {@link NoopPasswordResetNotifier}.
 *
 * <p>A write failure is logged and swallowed. It must not surface as an error response: UC-03 B3
 * requires the same generic answer for every address, and a distinguishable failure would reveal
 * which addresses exist.
 */
public class FilePasswordResetNotifier implements PasswordResetNotifier {

    private static final Logger log = LoggerFactory.getLogger(FilePasswordResetNotifier.class);

    private final Path sinkFile;

    public FilePasswordResetNotifier(Path sinkFile) {
        this.sinkFile = sinkFile;
    }

    @Override
    public void sendPasswordResetLink(String email, String resetLink) {
        String line = "%s | %s | %s%n".formatted(Instant.now(), email, resetLink);
        try {
            Path parent = sinkFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(sinkFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            // Neither the link nor the address is logged: the link carries the token, and the
            // address would tell anyone reading the log which accounts exist (UC-03 A2). The
            // sink file already holds both for the developer who needs them.
            log.debug("Password reset link written to the development sink");
        } catch (IOException ex) {
            log.warn("Could not write the development password reset sink; the request still "
                    + "succeeds so the response stays identical for every address", ex);
        }
    }
}
