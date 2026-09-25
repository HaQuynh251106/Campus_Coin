package com.campuscoin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Campus Coin REST API.
 *
 * <p>{@code @ConfigurationPropertiesScan} is enabled so that typed configuration records such as
 * {@code JwtProperties} bind from {@code application.yml} without a hand-written
 * {@code @EnableConfigurationProperties} list that has to be kept in step with the classes.
 *
 * <p>{@link UserDetailsServiceAutoConfiguration} is excluded deliberately. The application has no
 * {@code UserDetailsService} - authentication is the JWT bearer filter plus the authentication
 * service, which reads accounts from the database - so Spring Boot would otherwise create a
 * default in-memory user and log a generated password for it at startup:
 *
 * <pre>Using generated security password: 8f13...-...-...</pre>
 *
 * <p>That account cannot authenticate against this API (no HTTP Basic or form login is enabled),
 * so the line is not a vulnerability - but it is a credential-shaped string written to the
 * application log that never should be there, and on a shared or aggregated log it invites
 * exactly the wrong investigation. Excluding the auto-configuration removes the account and the
 * log line together.
 *
 * <p>{@link EnableScheduling} is enabled for one job: the recurring-transaction scheduler
 * (UC-09), which calls {@code sp_post_recurring_transactions} once a day. It is declared here
 * because a {@code @Scheduled} method is silently inert without it - the application would start
 * normally and simply never post a recurring transaction, which is the kind of failure that looks
 * like a business-rule problem rather than a missing annotation. The job is switched off in the
 * test environment so it cannot write rows during an assertion.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class CampusCoinApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusCoinApplication.class, args);
    }
}
