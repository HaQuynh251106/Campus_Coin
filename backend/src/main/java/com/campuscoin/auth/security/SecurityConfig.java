package com.campuscoin.auth.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.campuscoin.auth.service.SessionService;

/**
 * The security policy for the whole API.
 *
 * <p>Design decisions, each tied to a requirement:
 *
 * <ul>
 *   <li><b>Stateless.</b> Authentication is a signed bearer token, so
 *       {@link SessionCreationPolicy#STATELESS} is set and Spring Security keeps no HTTP session.
 *       UC-02 B3's "security session" is the {@code user_sessions} row, which the application
 *       controls and can revoke; an HTTP session could not be revoked that way.</li>
 *   <li><b>CSRF disabled.</b> CSRF defends requests whose credentials the browser attaches
 *       automatically, such as cookies. This API reads the token from an explicit
 *       {@code Authorization} header, which a cross-site form post cannot set. Enabling CSRF
 *       would break the documented contract without adding protection. This is recorded in
 *       {@code docs/SECURITY.md}.</li>
 *   <li><b>Deny by default.</b> Only the endpoints that a use case opens to anonymous callers
 *       are permitted; everything else under {@code /api/**} requires authentication. A new
 *       endpoint in a later module is therefore protected unless someone deliberately opens it
 *       here.</li>
 *   <li><b>Role rules.</b> {@code /api/v1/admin/**} requires {@code ADMIN}. That is the
 *       server-side half of UC-05 E1: a student who navigates to an administrator URL is refused
 *       by the API, not merely by the interface. The student-facing paths
 *       ({@code /profile/**}, {@code /categories/**}, {@code /transactions/**},
 *       {@code /recurring-rules/**}, {@code /budgets/**}, {@code /notifications/**},
 *       {@code /dashboard/**}) require {@code STUDENT} for the mirror-image reason: each
 *       use case is a student acting on their own data, and the {@code /api/**} catch-all below
 *       would otherwise admit an administrator token through a student-facing route.</li>
 * </ul>
 *
 * <p>The administrator sign-in endpoint itself is {@code /api/v1/admin/auth/login} and must stay
 * anonymous - nobody can present a token before signing in - so that one path is permitted
 * explicitly and the role check for it happens in the authentication service, which is what
 * UC-05 A1 asks for (a student is rejected with 403, not merely refused a token).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Endpoints anonymous callers may reach, each because a use case starts there. */
    private static final String[] PUBLIC_POST_ENDPOINTS = {
            "/api/v1/auth/register",                 // UC-01
            "/api/v1/auth/login",                    // UC-02
            "/api/v1/auth/password-reset/request",   // UC-03 B2
            "/api/v1/auth/password-reset/verify",    // UC-03 B5
            "/api/v1/auth/password-reset/complete",  // UC-03 B7
            "/api/v1/admin/auth/login"               // UC-05 B1 (role checked in AuthService)
    };

    /**
     * API documentation and the liveness probe. These expose no application data and no
     * credentials, so they stay reachable without a token.
     *
     * <p>The list is exhaustive and matched against {@code /actuator/**} explicitly below, rather
     * than relying on the final {@code permitAll}. {@code management.endpoints.web.exposure}
     * also lists {@code metrics}: if a path were not named here it would fall through to the
     * catch-all and become readable by anyone, so the rule below closes that gap.
     */
    private static final String[] PUBLIC_READ_ENDPOINTS = {
            "/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/health",
            "/actuator/info"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   SessionService sessionService,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler,
                                                   CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        // Built here rather than declared as a bean: Spring Boot registers every Filter bean with
        // the servlet container as well as putting it in the security chain, which would run the
        // token check twice per request.
        JwtAuthenticationFilter jwtAuthenticationFilter =
                new JwtAuthenticationFilter(jwtService, sessionService, authenticationEntryPoint);

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_READ_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Everything else under /actuator (metrics today, more later) reports on
                        // the application's internals. Only the two paths named above are open, so
                        // this rule makes the rest unreachable rather than quietly readable - which
                        // is what the final permitAll would otherwise do.
                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // UC-04 and UC-27 are a student's own profile and preferences. There is no
                        // administrator route to these either: UC-22's "edit a user" is a different
                        // operation with its own contract under /api/v1/admin/**. The rule is
                        // stated explicitly rather than left to the /api/** catch-all below,
                        // because that catch-all admits any authenticated caller - so without this
                        // line an administrator token would reach the student profile endpoints,
                        // which is the same leak the three role rules below each close.
                        .requestMatchers("/api/v1/profile/**").hasRole("STUDENT")
                        // UC-06 is a student's own categories. The administrator route to the
                        // same table is UC-20, which is sp_admin_upsert_default_category and
                        // lives under /api/v1/admin/**. An administrator whose only identifier
                        // carries two roles could otherwise reach these endpoints and have the
                        // service write a row owned by themselves, quietly creating a personal
                        // category through a student-facing API. Refusing the role here keeps the
                        // two routes to `categories` separate.
                        .requestMatchers("/api/v1/categories/**").hasRole("STUDENT")
                        // UC-07 and UC-10 are a student's own records. Administrators have no
                        // endpoint that writes a transaction: the administrative reports (UC-21,
                        // UC-22) read aggregates through /api/v1/admin/**, and the seeded data they
                        // look at was never entered by an administrator. Refusing the role here
                        // keeps a transaction owned by an administrator from being created through
                        // a student-facing API.
                        .requestMatchers("/api/v1/transactions/**").hasRole("STUDENT")
                        // UC-09 is a student's own rules. Administrators have no endpoint here:
                        // the scheduler posts transactions on students' behalf, and the seeded
                        // rules are read by the students who own them. Refusing the role keeps a
                        // rule owned by an administrator from being created through a
                        // student-facing API - the same reasoning as the two rules above.
                        .requestMatchers("/api/v1/recurring-rules/**").hasRole("STUDENT")
                        // UC-13 is a student's own spending limits. There is no administrator
                        // route to a budget: the administrator's reports (UC-21, UC-22) read
                        // aggregates through /api/v1/admin/**, and nothing there writes a limit on
                        // a student's behalf. Refusing the role keeps a limit owned by an
                        // administrator from being set through a student-facing API.
                        .requestMatchers("/api/v1/budgets/**").hasRole("STUDENT")
                        // UC-14 is a student's own messages. This is the rule that matters most of
                        // the student rules here: a notification's title and body are readable
                        // prose about one student's spending, so admitting an administrator token
                        // would let a role that has no use case for these rows read them. An
                        // administrator sending an announcement (UC-21) does so through
                        // /api/v1/admin/**, which is a different operation on the same table.
                        .requestMatchers("/api/v1/notifications/**").hasRole("STUDENT")
                        // UC-12 is a student's own dashboard. It is the most personal read in the
                        // API: one response carries the month's income and spending, the category
                        // most was spent on, the tips generated from the student's own habits and
                        // the notices addressed to them. Administrators have no route here and no
                        // use case for one - UC-23's usage statistics are aggregates over many
                        // students, not one student's figures - so admitting the role would let it
                        // read a named student's spending through a route that was never meant to
                        // name anyone. Refusing the role is what keeps that impossible rather than
                        // merely discouraged.
                        .requestMatchers("/api/v1/dashboard/**").hasRole("STUDENT")
                        // UC-15 is a student's own financial report: one month's income and
                        // spending, every category's share of it, and the six-month trend built
                        // from the student's own records. An administrator has a use case for
                        // aggregate reporting (UC-21) but none for a named student's figures, and
                        // UC-21's reads live under /api/v1/admin/** where they are sums over many
                        // students rather than one student's rows. Admitting the role here would
                        // expose exactly the per-student detail the administrative reports are
                        // deliberately built not to name. Reports are also where UC-16 exports
                        // from, so this rule is what keeps an export a student's own.
                        .requestMatchers("/api/v1/reports/**").hasRole("STUDENT")
                        // UC-18 is a student's own saving tips. A tip's title and body are readable
                        // prose rendered from the student's own figures - it names the category they
                        // spent in and the amount they spent - so admitting an administrator token
                        // would let a role with no use case for these rows read a named student's
                        // spending. The dashboard rule above already excludes the same rows from the
                        // administrator's reach through /api/v1/dashboard/**; this closes the
                        // student-facing route to the very table the dashboard reads them from.
                        // Administrator work on tip templates (UC-20) is a different operation on
                        // `tip_templates`, not on a student's `user_tips`, and lives under
                        // /api/v1/admin/**.
                        .requestMatchers("/api/v1/tips/**").hasRole("STUDENT")
                        .requestMatchers("/api/**").authenticated()
                        // Kept permissive because Spring's own /error dispatch and the static
                        // Swagger UI assets live outside /api/**. Every application endpoint is
                        // under /api/**, so the two rules above cover all of them.
                        .anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // The bearer filter runs before the username/password filter so a token is
                // resolved into the SecurityContext before authorisation is evaluated.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BCrypt at strength 10.
     *
     * <p>Strength 10 is not an arbitrary choice: the seeded accounts in
     * {@code db/05_seed.sql} carry {@code $2y$10$} hashes, and Spring's implementation verifies
     * the {@code $2y} variant, so seeded and newly registered accounts are checked by the same
     * encoder. Passwords are only ever hashed and verified - never decrypted, never stored, and
     * never written to a log (BR-01, section 7.2).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
