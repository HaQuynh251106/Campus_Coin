package com.campuscoin.auth.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import com.campuscoin.auth.service.SessionService;

/**
 * Turns a {@code Authorization: Bearer <token>} header into an authenticated request.
 *
 * <p>Runs once per request, before the rest of the chain. A request without a header is simply
 * left unauthenticated - public endpoints such as sign-in and registration must stay reachable -
 * and the authorisation rules decide the rest.
 *
 * <p>A present token is checked in three steps, and all three matter:
 * <ol>
 *   <li>Signature, issuer and expiry, by {@link JwtService}.</li>
 *   <li>The session row behind the token's hash must exist, not be revoked and not be past its
 *       expiry. This is what makes sign-out real: UC-02 B5 revokes the row and the token stops
 *       working at once, even though its own {@code exp} has not passed (UC-02 A3).</li>
 *   <li>The account must still be ACTIVE and its {@code token_version} must equal the {@code tv}
 *       claim. BR-03 bumps the column - on a password reset and on an account disable - so every
 *       token issued earlier dies immediately.</li>
 * </ol>
 *
 * <p>Any failure is answered as 401 through the entry point rather than thrown: the security
 * chain sits outside the DispatcherServlet, so {@code GlobalExceptionHandler} would never see it
 * and the client would get the container's default error page instead of the documented body.
 *
 * <p>The token is never logged, and neither is the header.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final SessionService sessionService;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   SessionService sessionService,
                                   AuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AuthenticatedUser user = sessionService.authenticateToken(token);
            var authentication = new UsernamePasswordAuthenticationToken(
                    user, null,
                    java.util.List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (RuntimeException ex) {
            // Covers a bad signature, an expired token, a revoked or unknown session, a disabled
            // account and a stale token_version. None of these should be distinguishable to the
            // caller, and none should be logged with the token, so only the class is recorded.
            SecurityContextHolder.clearContext();
            log.debug("Rejected bearer token path={} reason={}",
                    request.getRequestURI(), ex.getClass().getSimpleName());
            authenticationEntryPoint.commence(request, response,
                    new InsufficientAuthenticationException("Invalid or expired access token", ex));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
