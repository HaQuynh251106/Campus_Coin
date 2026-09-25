package com.campuscoin.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The cross-cutting guarantees of the administration surface, asserted once for all sixteen routes.
 *
 * <p><b>Why one suite rather than a test in each resource's class.</b> The property being pinned here
 * is not about categories or announcements - it is that {@code SecurityConfig}'s
 * {@code /api/v1/admin/**} rule is the whole authorisation statement for this module and that every
 * route added later lands inside it. Stating it sixteen times would make it sixteen tests that can
 * individually be deleted; stating it once over the whole list means a route that is added to the
 * module but escapes the rule fails one clear assertion.
 *
 * <p>The list of routes is deliberately written out rather than derived from the controllers, so that
 * a newly mapped route which nobody added here is a visible gap rather than a silent pass.
 * {@code OpenApiContractIT} covers the other direction: every route in the document must be in the
 * inventory.
 */
class AdminSecurityIT extends AbstractAdminApiIT {

    /** The path id the two templates are instantiated with. Any value does; none is reached. */
    private static final long UNREACHABLE_ID = 999_999L;

    private static final String UNREACHABLE_KEY = "no.such.setting";

    /**
     * The sixteen operations with concrete paths, and a body that would be valid were the caller
     * allowed through.
     *
     * <p>The bodies matter: without one, a {@code POST} that got past the filter chain would fail
     * with a {@code 400} from validation and the test would report the wrong reason. Every request
     * here is one that would succeed if the role rule did not exist.
     *
     * <p>A {@code LinkedHashMap} rather than an assertion-ordered collection, because the map is
     * iterated to build the parameterised cases and a stable order makes a failure list readable.
     */
    private static Map<String, Object> routes() {
        Map<String, Object> routes = new LinkedHashMap<>();
        routes.put("GET " + ADMIN_USERS_URL, null);
        routes.put("POST " + ADMIN_USERS_URL + "/" + UNREACHABLE_ID + "/status",
                Map.of("status", "DISABLED"));
        routes.put("POST " + ADMIN_USERS_URL + "/" + UNREACHABLE_ID + "/password-reset", null);
        routes.put("GET " + ADMIN_CATEGORIES_URL, null);
        routes.put("POST " + ADMIN_CATEGORIES_URL,
                Map.of("name", "Security Sweep Category", "type", "EXPENSE"));
        routes.put("PATCH " + ADMIN_CATEGORIES_URL + "/" + UNREACHABLE_ID,
                Map.of("name", "Security Sweep Category"));
        routes.put("GET " + ADMIN_ANNOUNCEMENTS_URL, null);
        routes.put("POST " + ADMIN_ANNOUNCEMENTS_URL,
                Map.of("title", "Security sweep", "body", "A notice no student may publish."));
        routes.put("PATCH " + ADMIN_ANNOUNCEMENTS_URL + "/" + UNREACHABLE_ID,
                Map.of("isActive", false));
        routes.put("GET " + ADMIN_TIP_TEMPLATES_URL, null);
        routes.put("POST " + ADMIN_TIP_TEMPLATES_URL,
                Map.of("code", "SECURITY_SWEEP", "titleTemplate", "Title", "bodyTemplate", "Body",
                        "defaultPriority", 100));
        routes.put("PATCH " + ADMIN_TIP_TEMPLATES_URL + "/" + UNREACHABLE_ID,
                Map.of("titleTemplate", "Title"));
        routes.put("GET " + ADMIN_SETTINGS_URL, null);
        routes.put("PATCH " + ADMIN_SETTINGS_URL + "/" + UNREACHABLE_KEY, Map.of("value", "90"));
        routes.put("GET " + ADMIN_STATS_URL, null);
        routes.put("GET " + ADMIN_STATS_TOP_CATEGORIES_URL, null);
        return routes;
    }

    private static Stream<Arguments> routesAsArguments() {
        return routes().entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    /**
     * The sweep tests exactly the sixteen declared operations.
     *
     * <p>{@link AbstractAdminApiIT#MODULE_11_ROUTES} is the declared inventory; asserting the two
     * agree is what stops this file from quietly testing fifteen routes after somebody adds a
     * sixteenth.
     *
     * <p><b>The two lists are written differently on purpose and the comparison instantiates one into
     * the other.</b> The declaration spells the two templated paths as {@code /{id}} and {@code /{key}}
     * because that is the shape the inventory publishes and the shape a reader recognises; this file's
     * map spells every path concretely because the parameterised sweep has to actually request one. So
     * the declaration is instantiated with the same two placeholder values the map uses, and the two are
     * compared as concrete strings. Asserting the declared set directly would compare
     * {@code "/{id}/status"} against {@code "/999999/status"} and could never pass, which would make
     * this test a permanent failure rather than a guard.
     */
    @Test
    @DisplayName("Module 11: the security sweep covers exactly the sixteen declared operations")
    void theSweepCoversEveryDeclaredRoute() {
        assertThat(routes().keySet())
                .containsExactlyInAnyOrderElementsOf(instantiatedDeclaredRoutes());
        assertThat(routes()).hasSize(16);
    }

    /**
     * The declared inventory with its two templates instantiated, so it can be compared to the sweep.
     *
     * <p>Reads the same list {@code OpenApiContractIT} checks against the generated document, so a route
     * added to the module and forgotten here fails this test rather than escaping both sweeps.
     */
    private static List<String> instantiatedDeclaredRoutes() {
        return MODULE_11_ROUTES.stream()
                .map(route -> route
                        .replace("{id}", Long.toString(UNREACHABLE_ID))
                        .replace("{key}", UNREACHABLE_KEY))
                .toList();
    }

    @ParameterizedTest(name = "a student token is refused with 403 on {0}")
    @MethodSource("routesAsArguments")
    @DisplayName("UC-05 E1: no module 11 route admits a student token")
    void studentTokenIsForbiddenOnEveryRoute(String route, Object body) throws Exception {
        String token = studentToken();

        ResponseEntity<String> response = send(methodOf(route), pathOf(route), token, body);

        // 403 and not 401: the caller is authenticated, and the answer is that this identity may not
        // use this route. Anything else - a 404 from the controller not being reached, a 400 from
        // validation, or worse a 2xx - would mean the rule is not doing the work.
        assertThat(response.getStatusCode())
                .as("%s with a student token body=%s", route, response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ACCESS_DENIED");
    }

    @ParameterizedTest(name = "no token is refused with 401 on {0}")
    @MethodSource("routesAsArguments")
    @DisplayName("Section 7.5: no module 11 route is reachable without a token")
    void aMissingTokenIsUnauthenticatedOnEveryRoute(String route, Object body) {
        ResponseEntity<String> response = send(methodOf(route), pathOf(route), null, body);

        assertThat(response.getStatusCode())
                .as("%s with no token body=%s", route, response.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("BR-03: an administrator disabled after signing in is refused immediately")
    void anAdministratorDisabledAfterSigningInIsRefusedImmediately() throws Exception {
        String email = randomEmail();
        registerStudent(email, PASSWORD);
        promoteToAdmin(email);
        String token = loginAt(ADMIN_LOGIN_URL, email, PASSWORD);

        // The token works while the account is ACTIVE...
        assertThat(get(ADMIN_USERS_URL, token).getStatusCode()).isEqualTo(HttpStatus.OK);

        // ...and stops as soon as it is not. Three independent reasons, and the test does not
        // distinguish them because BR-03 does not either: the session row is revoked, the
        // token_version the JWT was signed against no longer matches, and sp_require_admin reads
        // users.status on every write. Each alone would be sufficient; all three together is what
        // makes "disable means disable" hold even if one of them is ever changed.
        Long userId = longValueFrom("SELECT id FROM users WHERE email = ?", email);
        runInDatabase("UPDATE users SET status = 'DISABLED', token_version = token_version + 1 "
                + "WHERE id = ?", userId);
        runInDatabase("UPDATE user_sessions SET revoked_at = NOW(), revoked_reason = 'ADMIN_DISABLE' "
                + "WHERE user_id = ? AND revoked_at IS NULL", userId);

        ResponseEntity<String> afterDisable = get(ADMIN_USERS_URL, token);
        assertThat(afterDisable.getStatusCode())
                .as("body=%s", afterDisable.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * A student account promoted to {@code ADMIN} accepts <em>both</em> of its tokens, and neither is
     * widened or narrowed by the promotion.
     *
     * <p><b>This asserts the behaviour the code actually has, and the direction is deliberate.</b> The
     * authority for a request is <em>not</em> read from the JWT's {@code role} claim: the claim is
     * decoded into {@code JwtClaims} and then never used. {@code SessionService.authenticateToken}
     * loads the {@code users} row on every request and builds the {@code AuthenticatedUser} from
     * {@code user.getRole()}, and only the token's {@code tv} claim is compared against the row (BR-03).
     * So the effective role of any token is whatever the row says at the moment it is presented, and a
     * promotion takes effect on the token already in circulation - there is no re-sign-in.
     *
     * <p>That is coherent with the rest of the model and is why it is recorded rather than "fixed": the
     * row is already the authority for {@code status} and {@code token_version}, both of which are
     * re-read per request for exactly this reason. Reading the role from the claim as well would make
     * the role the one account attribute a stale token could assert about itself. The safe direction
     * here is the live row, in both directions - a demotion takes effect immediately too, which the
     * disable test above pins for {@code status} and which is the more important half.
     *
     * <p>Both tokens are asserted because the interesting property is the pair: the student's own token,
     * minted while the row said {@code STUDENT}, now reaches an administrator route; an administrator's
     * token is unaffected. A test that only checked the first would pass if the role were read from the
     * claim and the account happened to be re-read anyway.
     */
    @Test
    @DisplayName("BR-03: authority follows the account's role, not the token's role claim")
    void promotionTakesEffectOnTheTokenAlreadyInCirculation() throws Exception {
        String email = randomEmail();
        registerStudent(email, PASSWORD);
        // Minted while the row says STUDENT, so the claim inside it says STUDENT too.
        String studentToken = loginAt(LOGIN_URL, email, PASSWORD);
        String adminToken = adminToken();

        // Before the promotion the route is refused, which is the control: the same token is rejected
        // for the reason this test is about, so a later 200 cannot be a route that never checked.
        assertThat(get(ADMIN_USERS_URL, studentToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        runInDatabase("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);

        assertThat(get(ADMIN_USERS_URL, studentToken).getStatusCode())
                .as("the live row is the authority, so the promotion applies to this token")
                .isEqualTo(HttpStatus.OK);
        assertThat(get(ADMIN_USERS_URL, adminToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * No module 11 response carries a forbidden key, over every read endpoint at once.
     *
     * <p>The non-triviality guard is the point of this test rather than an afterthought: a recursive
     * key scan that collected nothing, or that was pointed at the wrong node, would pass silently.
     * The control payload is built from the same shape as the real ones and deliberately carries a
     * forbidden key, so the scan is shown to reject a payload that has one before the real payloads
     * are checked.
     */
    @Test
    @DisplayName("Section 7.2: no module 11 response publishes a password, hash or internal token")
    void noResponsePublishesAForbiddenKey() throws Exception {
        JsonNode control = objectMapper.readTree("{\"users\":[{\"id\":1,\"tokenVersion\":3}]}");
        assertThat(allKeysIn(control)).contains("tokenVersion");
        assertThat(allKeysIn(control)).anyMatch(FORBIDDEN_RESPONSE_KEYS::contains);

        String token = adminToken();

        List<JsonNode> payloads = List.of(
                ok(HttpMethod.GET, ADMIN_USERS_URL, token, null),
                ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, token, null),
                ok(HttpMethod.GET, ADMIN_ANNOUNCEMENTS_URL, token, null),
                ok(HttpMethod.GET, ADMIN_TIP_TEMPLATES_URL, token, null),
                ok(HttpMethod.GET, ADMIN_SETTINGS_URL, token, null),
                ok(HttpMethod.GET, ADMIN_STATS_URL, token, null),
                ok(HttpMethod.GET, ADMIN_STATS_TOP_CATEGORIES_URL, token, null));

        for (JsonNode payload : payloads) {
            assertThat(allKeysIn(payload))
                    .as("payload %s", payload)
                    .doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_KEYS);
        }

        // The writes return a resource rather than a list, so one of each kind is scanned too.
        // `created` rather than `ok`: POSTing a new template is a 201, and the helper that asserts 200
        // would report the correct status as the failure.
        JsonNode createdTemplate = created(ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", randomTipTemplateCode(),
                "titleTemplate", "Scanned template",
                "bodyTemplate", "A template that exists only to be scanned.",
                "defaultPriority", 100));
        assertThat(allKeysIn(createdTemplate)).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_KEYS);

        String targetEmail = randomEmail();
        Long targetId = registeredStudentId(targetEmail);
        ResponseEntity<String> reset = send(HttpMethod.POST,
                ADMIN_USERS_URL + "/" + targetId + "/password-reset", token, null);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode resetBody = body(reset);
        assertThat(allKeysIn(resetBody)).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_KEYS);
        // The reset response is a message and nothing else: the raw link is never returned here.
        assertThat(fieldNamesOf(resetBody)).containsExactly("message");
    }

    @Test
    @DisplayName("Module 11: an unknown path under the admin prefix is 404, not 403")
    void anUnknownAdminRouteIsNotFoundForAnAdministrator() throws Exception {
        String token = adminToken();

        // The role rule admits the administrator, so the dispatcher is reached and reports that no
        // mapping exists. This is what distinguishes "no such path" from "not for you", and it is
        // why the sweep above expects 403 rather than 404 for the sixteen real routes.
        ResponseEntity<String> response =
                get(ADMIN_USERS_URL + "/999999/unknown-sub-resource", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCodeOf(response)).isEqualTo("NOT_FOUND");
    }

    private static HttpMethod methodOf(String route) {
        return HttpMethod.valueOf(route.substring(0, route.indexOf(' ')));
    }

    private static String pathOf(String route) {
        return route.substring(route.indexOf(' ') + 1);
    }
}
