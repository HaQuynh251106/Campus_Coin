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

class AdminSecurityIT extends AbstractAdminApiIT {

    private static final long UNREACHABLE_ID = 999_999L;

    private static final String UNREACHABLE_KEY = "no.such.setting";

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

    @Test
    @DisplayName("Module 11: the security sweep covers exactly the sixteen declared operations")
    void theSweepCoversEveryDeclaredRoute() {
        assertThat(routes().keySet())
                .containsExactlyInAnyOrderElementsOf(instantiatedDeclaredRoutes());
        assertThat(routes()).hasSize(16);
    }

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

        assertThat(get(ADMIN_USERS_URL, token).getStatusCode()).isEqualTo(HttpStatus.OK);

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

    @Test
    @DisplayName("BR-03: authority follows the account's role, not the token's role claim")
    void promotionTakesEffectOnTheTokenAlreadyInCirculation() throws Exception {
        String email = randomEmail();
        registerStudent(email, PASSWORD);

        String studentToken = loginAt(LOGIN_URL, email, PASSWORD);
        String adminToken = adminToken();

        assertThat(get(ADMIN_USERS_URL, studentToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        runInDatabase("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);

        assertThat(get(ADMIN_USERS_URL, studentToken).getStatusCode())
                .as("the live row is the authority, so the promotion applies to this token")
                .isEqualTo(HttpStatus.OK);
        assertThat(get(ADMIN_USERS_URL, adminToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

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

        assertThat(fieldNamesOf(resetBody)).containsExactly("message");
    }

    @Test
    @DisplayName("Module 11: an unknown path under the admin prefix is 404, not 403")
    void anUnknownAdminRouteIsNotFoundForAnAdministrator() throws Exception {
        String token = adminToken();

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
