package com.campuscoin.auth.security;

import java.nio.file.Path;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses how a password reset link is delivered.
 *
 * <p>A conditional bean rather than an {@code if} inside the service, so the service depends only
 * on the {@link PasswordResetNotifier} port and the choice of transport stays a deployment
 * concern. Swapping in a real mail client later means adding one bean, not editing UC-03 code.
 *
 * <p>The development sink writes to {@code backend/target/}, which is already gitignored, so a
 * generated token can never be committed.
 */
@Configuration
public class PasswordResetConfig {

    @Bean
    @ConditionalOnMissingBean(PasswordResetNotifier.class)
    public PasswordResetNotifier passwordResetNotifier(PasswordResetProperties properties) {
        boolean sinkEnabled = Boolean.TRUE.equals(properties.sinkEnabled());
        if (sinkEnabled) {
            String configured = properties.sinkFile();
            Path sinkFile = configured == null || configured.isBlank()
                    ? Path.of("target", "password-reset-dev.log")
                    : Path.of(configured);
            return new FilePasswordResetNotifier(sinkFile);
        }
        return new NoopPasswordResetNotifier();
    }
}
