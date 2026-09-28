package com.campuscoin.auth.security;

import java.nio.file.Path;
import java.util.Properties;

import com.campuscoin.auth.security.PasswordResetProperties.Smtp;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
public class PasswordResetConfig {

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
