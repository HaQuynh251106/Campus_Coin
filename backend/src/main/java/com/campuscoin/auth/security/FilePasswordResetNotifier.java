package com.campuscoin.auth.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

            log.debug("Password reset link written to the development sink");
        } catch (IOException ex) {
            log.warn("Could not write the development password reset sink; the request still "
                    + "succeeds so the response stays identical for every address", ex);
        }
    }
}
