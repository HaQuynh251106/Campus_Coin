package com.campuscoin.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.campuscoin.auth.security.PasswordResetProperties.Smtp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class PasswordResetConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PasswordResetConfig.class);

    private static PasswordResetProperties properties(boolean sinkEnabled, String host, String fromAddress) {
        Smtp smtp = new Smtp(host, 587, null, null, true, false);
        return new PasswordResetProperties("http://localhost:4200/reset-password", sinkEnabled, null,
                fromAddress, "Campus Coin", smtp);
    }

    private ApplicationContextRunner configured(PasswordResetProperties properties) {
        return runner.withBean(PasswordResetProperties.class, () -> properties);
    }

    @Test
    @DisplayName("The development sink wins when it is switched on")
    void theSinkWinsWhenEnabled() {
        configured(properties(true, "smtp.example.com", "no-reply@campus.example"))
                .run(context -> assertThat(context.getBean(PasswordResetNotifier.class))
                        .isInstanceOf(FilePasswordResetNotifier.class));
    }

    @Test
    @DisplayName("With the sink off and a host and sender set, the SMTP notifier is installed")
    void smtpIsInstalledWhenConfigured() {
        configured(properties(false, "smtp.example.com", "no-reply@campus.example"))
                .run(context -> assertThat(context.getBean(PasswordResetNotifier.class))
                        .isInstanceOf(SmtpPasswordResetNotifier.class));
    }

    @Test
    @DisplayName("A blank host is 'no mail server', so the no-op is installed")
    void aBlankHostYieldsTheNoop() {
        configured(properties(false, "", "no-reply@campus.example"))
                .run(context -> assertThat(context.getBean(PasswordResetNotifier.class))
                        .isInstanceOf(NoopPasswordResetNotifier.class));
    }

    @Test
    @DisplayName("A host with no sender address cannot deliver, so the no-op is installed")
    void aMissingSenderYieldsTheNoop() {
        configured(properties(false, "smtp.example.com", ""))
                .run(context -> assertThat(context.getBean(PasswordResetNotifier.class))
                        .isInstanceOf(NoopPasswordResetNotifier.class));
    }

    @Test
    @DisplayName("The built sender carries the host, port and STARTTLS decision")
    void theSenderIsConfiguredFromTheProperties() {
        JavaMailSender sender = PasswordResetConfig.mailSender(
                new Smtp("smtp.example.com", 2525, "apikey", "secret", true, false));

        assertThat(sender).isInstanceOf(JavaMailSenderImpl.class);
        JavaMailSenderImpl impl = (JavaMailSenderImpl) sender;
        assertThat(impl.getHost()).isEqualTo("smtp.example.com");
        assertThat(impl.getPort()).isEqualTo(2525);
        assertThat(impl.getUsername()).isEqualTo("apikey");

        assertThat(impl.getPassword()).isEqualTo("secret");
        assertThat(impl.getJavaMailProperties())
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.ssl.enable", "false");
    }

    @Test
    @DisplayName("A port is optional and defaults to the submission port")
    void thePortDefaults() {
        JavaMailSenderImpl sender = (JavaMailSenderImpl) PasswordResetConfig.mailSender(
                new Smtp("smtp.example.com", null, null, null, true, false));

        assertThat(sender.getPort()).isEqualTo(587);

        assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.auth", "false");
    }
}
