package com.campuscoin.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.campuscoin.auth.entity.User;
import com.campuscoin.auth.repository.UserRepository;
import com.campuscoin.support.AbstractMySqlIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ProfileApiIT extends AbstractMySqlIntegrationTest {

    private static final String PROFILE_URL = "/api/v1/profile/me";
    private static final String PREFERENCES_URL = "/api/v1/profile/me/preferences";
    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String LOGOUT_URL = "/api/v1/auth/logout";

    private static final String PASSWORD = "Student@123";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("UC-04/UC-27: a new student reads their own profile with the schema defaults")
    void newStudentProfileUsesTheSchemaDefaults() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        ResponseEntity<String> response = send(HttpMethod.GET, PROFILE_URL, token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("fullName").asText()).isEqualTo("Test Student");
        assertThat(body.get("email").asText()).isEqualTo(email);

        assertThat(body.get("academicYear").isNull()).isTrue();
        assertThat(body.get("monthlyAllowanceBaseline").decimalValue())
                .isEqualByComparingTo("0.00");
        assertThat(body.get("monthlySavingsGoal").decimalValue()).isEqualByComparingTo("0.00");

        assertThat(body.get("themePreference").asText()).isEqualTo("SYSTEM");
        assertThat(body.get("fontScale").asText()).isEqualTo("MEDIUM");

        assertThat(body.get("currency").asText()).isEqualTo("USD");
    }

    @Test
    @DisplayName("Section 7.7: the profile response carries no security or internal fields")
    void profileResponseExposesNoSensitiveFields() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        String body = send(HttpMethod.GET, PROFILE_URL, token, null).getBody();

        assertThat(body).doesNotContain("passwordHash", "password_hash", "tokenVersion",
                "token_version", "lastLoginAt", "emailVerifiedAt", "aiEnabled", "status", "role");
    }

    @Test
    @DisplayName("UC-04: the profile fields are updated and persisted")
    void profileUpdatePersistsEveryField() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);
        Long userId = userIdOf(email);

        ResponseEntity<String> response = send(HttpMethod.PATCH, PROFILE_URL, token, Map.of(
                "fullName", "An Updated Name",
                "academicYear", "Year 3",
                "monthlyAllowanceBaseline", 650.50,
                "monthlySavingsGoal", 1500.00));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("fullName").asText()).isEqualTo("An Updated Name");
        assertThat(body.get("academicYear").asText()).isEqualTo("Year 3");
        assertThat(body.get("monthlyAllowanceBaseline").decimalValue()).isEqualByComparingTo("650.50");
        assertThat(body.get("monthlySavingsGoal").decimalValue()).isEqualByComparingTo("1500.00");

        assertThat(academicYearInDatabase(userId)).isEqualTo("Year 3");
        assertThat(moneyInDatabase(userId, "monthly_allowance_baseline"))
                .isEqualByComparingTo("650.50");

        JsonNode reread = objectMapper.readTree(
                send(HttpMethod.GET, PROFILE_URL, token, null).getBody());
        assertThat(reread.get("fullName").asText()).isEqualTo("An Updated Name");
    }

    @Test
    @DisplayName("UC-04: a field left out of the request is not changed")
    void omittedFieldsAreLeftAlone() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        send(HttpMethod.PATCH, PROFILE_URL, token, Map.of(
                "fullName", "First Name",
                "academicYear", "Year 2",
                "monthlyAllowanceBaseline", 400.00));

        ResponseEntity<String> response =
                send(HttpMethod.PATCH, PROFILE_URL, token, Map.of("monthlySavingsGoal", 900.00));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("fullName").asText()).isEqualTo("First Name");
        assertThat(body.get("academicYear").asText()).isEqualTo("Year 2");
        assertThat(body.get("monthlyAllowanceBaseline").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(body.get("monthlySavingsGoal").decimalValue()).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("UC-04: an empty academic year clears the field")
    void emptyAcademicYearClearsTheValue() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        send(HttpMethod.PATCH, PROFILE_URL, token, Map.of("academicYear", "Year 4"));
        assertThat(academicYearInDatabase(userIdOf(email))).isEqualTo("Year 4");

        ResponseEntity<String> response =
                send(HttpMethod.PATCH, PROFILE_URL, token, Map.of("academicYear", ""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).get("academicYear").isNull()).isTrue();
        assertThat(academicYearInDatabase(userIdOf(email))).isNull();
    }

    @Test
    @DisplayName("UC-04: zero is an accepted baseline and goal")
    void zeroMoneyValuesAreAccepted() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        ResponseEntity<String> response = send(HttpMethod.PATCH, PROFILE_URL, token, Map.of(
                "monthlyAllowanceBaseline", 0,
                "monthlySavingsGoal", 0));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).get("monthlySavingsGoal").decimalValue())
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("UC-04: the longest allowed name and academic year are accepted")
    void boundaryLengthsAreAccepted() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        String longestName = "A".repeat(120);
        String longestYear = "Y".repeat(30);

        ResponseEntity<String> response = send(HttpMethod.PATCH, PROFILE_URL, token, Map.of(
                "fullName", longestName,
                "academicYear", longestYear));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("fullName").asText()).hasSize(120);
        assertThat(body.get("academicYear").asText()).hasSize(30);
    }

    @Test
    @DisplayName("UC-04: surrounding whitespace is trimmed and the length rule follows the stored value")
    void whitespaceIsTrimmedBeforeTheLengthIsJudged() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);
        Long userId = userIdOf(email);

        assertFieldError(Map.of("fullName", " A "), "fullName");

        ResponseEntity<String> response =
                send(HttpMethod.PATCH, PROFILE_URL, token, Map.of("fullName", "  An Nguyen  "));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).get("fullName").asText())
                .isEqualTo("An Nguyen");
        assertThat(fullNameInDatabase(userId)).isEqualTo("An Nguyen");

        String paddedThirty = "  " + "Y".repeat(30) + "  ";
        ResponseEntity<String> padded = send(HttpMethod.PATCH, PROFILE_URL, token,
                Map.of("academicYear", paddedThirty));
        assertThat(padded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(padded.getBody()).get("academicYear").asText())
                .hasSize(30);
        assertThat(academicYearInDatabase(userId)).isEqualTo("Y".repeat(30));

        assertFieldError(Map.of("academicYear", "  " + "Y".repeat(31) + "  "), "academicYear");
    }

    @Test
    @DisplayName("UC-04: a name of 121 characters is rejected and 120 is accepted")
    void nameLengthBoundaryMatchesTheColumn() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        assertFieldError(Map.of("fullName", "A".repeat(121)), "fullName");

        assertThat(send(HttpMethod.PATCH, PROFILE_URL, token,
                Map.of("fullName", "A".repeat(120))).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("UC-27: a preference body that omits every field changes nothing")
    void emptyPreferenceBodyIsAcceptedAndChangesNothing() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);
        Long userId = userIdOf(email);

        send(HttpMethod.PATCH, PREFERENCES_URL, token, Map.of("themePreference", "DARK"));

        ResponseEntity<String> response = send(HttpMethod.PATCH, PREFERENCES_URL, token, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).get("themePreference").asText())
                .isEqualTo("DARK");
        assertThat(columnInDatabase(userId, "theme_pref")).isEqualTo("DARK");
    }

    @Test
    @DisplayName("UC-27: the display preferences are updated and persisted")
    void preferencesAreUpdated() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);
        Long userId = userIdOf(email);

        ResponseEntity<String> response = send(HttpMethod.PATCH, PREFERENCES_URL, token, Map.of(
                "themePreference", "DARK",
                "fontScale", "LARGE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("themePreference").asText()).isEqualTo("DARK");
        assertThat(body.get("fontScale").asText()).isEqualTo("LARGE");

        assertThat(columnInDatabase(userId, "theme_pref")).isEqualTo("DARK");
        assertThat(columnInDatabase(userId, "font_scale")).isEqualTo("LARGE");
    }

    @Test
    @DisplayName("UC-27: an omitted preference is left as it is")
    void omittedPreferenceIsLeftAlone() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        send(HttpMethod.PATCH, PREFERENCES_URL, token, Map.of("fontScale", "XLARGE"));
        ResponseEntity<String> response =
                send(HttpMethod.PATCH, PREFERENCES_URL, token, Map.of("themePreference", "LIGHT"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("themePreference").asText()).isEqualTo("LIGHT");

        assertThat(body.get("fontScale").asText()).isEqualTo("XLARGE");
    }

    @Test
    @DisplayName("UC-27: every ENUM member the schema defines is accepted")
    void everyEnumMemberIsAccepted() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        for (String theme : List.of("LIGHT", "DARK", "SYSTEM")) {
            for (String scale : List.of("SMALL", "MEDIUM", "LARGE", "XLARGE")) {
                ResponseEntity<String> response = send(HttpMethod.PATCH, PREFERENCES_URL, token,
                        Map.of("themePreference", theme, "fontScale", scale));
                assertThat(response.getStatusCode())
                        .as("theme=%s fontScale=%s", theme, scale)
                        .isEqualTo(HttpStatus.OK);
            }
        }
    }

    @Test
    @DisplayName("UC-04: an invalid profile field is a 400 naming that field")
    void invalidProfileFieldsAreReportedPerField() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        assertFieldError(Map.of("fullName", "A"), "fullName");
        assertFieldError(Map.of("fullName", "   "), "fullName");
        assertFieldError(Map.of("academicYear", "Y".repeat(31)), "academicYear");
        assertFieldError(Map.of("monthlyAllowanceBaseline", -0.01), "monthlyAllowanceBaseline");
        assertFieldError(Map.of("monthlySavingsGoal", -100), "monthlySavingsGoal");

        assertFieldError(Map.of("monthlyAllowanceBaseline", -1), "monthlyAllowanceBaseline");
    }

    @Test
    @DisplayName("UC-27: an unrecognised preference is a 400 naming that field")
    void unknownPreferenceValueIsReportedPerField() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        assertFieldError(PREFERENCES_URL, Map.of("themePreference", "BLUE"), "themePreference");
        assertFieldError(PREFERENCES_URL, Map.of("fontScale", "HUGE"), "fontScale");

        assertFieldError(PREFERENCES_URL, Map.of("themePreference", "dark"), "themePreference");
        assertFieldError(PREFERENCES_URL, Map.of("fontScale", 2), "fontScale");
    }

    @Test
    @DisplayName("Section 7.7: a rejected profile body leaves the row untouched")
    void rejectedUpdateWritesNothing() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);
        Long userId = userIdOf(email);

        send(HttpMethod.PATCH, PROFILE_URL, token, Map.of("academicYear", "Year 1"));

        ResponseEntity<String> response = send(HttpMethod.PATCH, PROFILE_URL, token, Map.of(
                "academicYear", "Year 5",
                "monthlySavingsGoal", -50));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(academicYearInDatabase(userId)).isEqualTo("Year 1");
        assertThat(moneyInDatabase(userId, "monthly_savings_goal")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("UC-04: money is limited to the column's two decimal places")
    void moneyBeyondTwoDecimalsIsRejected() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        assertFieldError(Map.of("monthlyAllowanceBaseline", 1.999), "monthlyAllowanceBaseline");
        assertFieldError(Map.of("monthlySavingsGoal", 0.001), "monthlySavingsGoal");

        assertThat(send(HttpMethod.PATCH, PROFILE_URL, token,
                Map.of("monthlySavingsGoal", 1234567890123.45)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertFieldError(Map.of("monthlySavingsGoal", 12345678901234.00), "monthlySavingsGoal");
    }

    @Test
    @DisplayName("UC-04: the same update applied twice leaves the same state")
    void repeatedIdenticalUpdatesAreIdempotent() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);
        Long userId = userIdOf(email);

        Map<String, Object> update = Map.of(
                "fullName", "Repeated Name",
                "academicYear", "Year 2",
                "monthlySavingsGoal", 750.00);

        for (int attempt = 0; attempt < 2; attempt++) {
            ResponseEntity<String> response = send(HttpMethod.PATCH, PROFILE_URL, token, update);
            assertThat(response.getStatusCode())
                    .as("attempt %d", attempt)
                    .isEqualTo(HttpStatus.OK);
        }

        assertThat(fullNameInDatabase(userId)).isEqualTo("Repeated Name");
        assertThat(academicYearInDatabase(userId)).isEqualTo("Year 2");
        assertThat(moneyInDatabase(userId, "monthly_savings_goal")).isEqualByComparingTo("750.00");
    }

    @Test
    @DisplayName("UC-04 / UC-27: a JSON number is coerced to text, but never resolved to an enum member")
    void jsonNumbersAreNeverResolvedToEnumPositions() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        ResponseEntity<String> coerced =
                send(HttpMethod.PATCH, PROFILE_URL, token, Map.of("academicYear", 3));
        assertThat(coerced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(coerced.getBody()).get("academicYear").asText())
                .isEqualTo("3");

        assertFieldError(PREFERENCES_URL, Map.of("fontScale", 3), "fontScale");
        assertFieldError(PREFERENCES_URL, Map.of("themePreference", 0), "themePreference");
    }

    @Test
    @DisplayName("Section 7.7: a malformed body is a 400 without internal detail")
    void malformedBodyIsRejectedWithoutLeakingInternals() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        ResponseEntity<String> response = restTemplate.exchange(PROFILE_URL, HttpMethod.PATCH,
                new HttpEntity<>("{ this is not json", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("MALFORMED_REQUEST");
        assertThat(response.getBody()).doesNotContain("JsonParseException", "com.fasterxml",
                "java.lang", "at com.campuscoin", "SQL");
    }

    @Test
    @DisplayName("Section 7.5: the profile endpoints require a token")
    void profileEndpointsRejectAnonymousCallers() throws Exception {
        assertThat(send(HttpMethod.GET, PROFILE_URL, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.PATCH, PROFILE_URL, null, Map.of("fullName", "Nobody")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.PATCH, PREFERENCES_URL, null, Map.of("fontScale", "LARGE")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Section 7.5: a malformed or tampered token is refused")
    void malformedTokensAreRefused() throws Exception {
        assertThat(send(HttpMethod.GET, PROFILE_URL, "not-a-jwt", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        String email = randomEmail();
        register(email);
        String token = login(email);

        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertThat(send(HttpMethod.GET, PROFILE_URL, tampered, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UC-02 B5: a signed-out session can no longer read or change the profile")
    void revokedSessionIsRefused() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        assertThat(send(HttpMethod.GET, PROFILE_URL, token, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        send(HttpMethod.POST, LOGOUT_URL, token, null);

        assertThat(send(HttpMethod.GET, PROFILE_URL, token, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.PATCH, PROFILE_URL, token, Map.of("fullName", "After Logout"))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("BR-03: a disabled account cannot read or change its profile")
    void disabledAccountIsRefused() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);
        Long userId = userIdOf(email);

        setStatus(userId, "DISABLED");

        assertThat(send(HttpMethod.GET, PROFILE_URL, token, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.PATCH, PROFILE_URL, token, Map.of("fullName", "Disabled"))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("BR-02: a profile update changes only the caller's own row")
    void profileUpdateTouchesOnlyTheCallersRow() throws Exception {
        String firstEmail = randomEmail();
        String secondEmail = randomEmail();
        register(firstEmail);
        register(secondEmail);
        String firstToken = login(firstEmail);
        String secondToken = login(secondEmail);

        Long firstId = userIdOf(firstEmail);
        Long secondId = userIdOf(secondEmail);

        send(HttpMethod.PATCH, PROFILE_URL, secondToken, Map.of(
                "fullName", "Second Student",
                "academicYear", "Year 4",
                "monthlySavingsGoal", 2000.00));

        send(HttpMethod.PATCH, PROFILE_URL, firstToken, Map.of(
                "fullName", "First Student",
                "academicYear", "Year 1",
                "monthlySavingsGoal", 100.00));
        send(HttpMethod.PATCH, PREFERENCES_URL, firstToken, Map.of("themePreference", "DARK"));

        assertThat(fullNameInDatabase(firstId)).isEqualTo("First Student");
        assertThat(academicYearInDatabase(firstId)).isEqualTo("Year 1");

        assertThat(fullNameInDatabase(secondId)).isEqualTo("Second Student");
        assertThat(academicYearInDatabase(secondId)).isEqualTo("Year 4");
        assertThat(moneyInDatabase(secondId, "monthly_savings_goal")).isEqualByComparingTo("2000.00");
        assertThat(columnInDatabase(secondId, "theme_pref")).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("Section 7.5: a profile request cannot promote the account or change its status")
    void profileRequestCannotEscalatePrivileges() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);
        Long userId = userIdOf(email);

        Map<String, Object> hostile = new LinkedHashMap<>();
        hostile.put("fullName", "Legitimate Name");
        hostile.put("role", "ADMIN");
        hostile.put("status", "DISABLED");
        hostile.put("email", "attacker@example.com");
        hostile.put("passwordHash", "$2y$10$injected");
        hostile.put("tokenVersion", 99);
        hostile.put("id", 1);

        ResponseEntity<String> response = send(HttpMethod.PATCH, PROFILE_URL, token, hostile);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fullNameInDatabase(userId)).isEqualTo("Legitimate Name");

        assertThat(columnInDatabase(userId, "role")).isEqualTo("STUDENT");
        assertThat(columnInDatabase(userId, "status")).isEqualTo("ACTIVE");
        assertThat(columnInDatabase(userId, "email")).isEqualTo(email);
        assertThat(columnInDatabase(userId, "token_version")).isEqualTo("0");
        assertThat(columnInDatabase(userId, "password_hash")).startsWith("$2");

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.has("role")).isFalse();
        assertThat(body.has("status")).isFalse();
        assertThat(body.has("passwordHash")).isFalse();
        assertThat(body.has("tokenVersion")).isFalse();
    }

    @Test
    @DisplayName("BR-03: saving a profile change writes only the changed column")
    void savingAProfileChangeWritesOnlyTheChangedColumn() throws Exception {
        String email = randomEmail();
        register(email);
        Long userId = userIdOf(email);

        new TransactionTemplate(transactionManager).execute(status -> {
            User user = userRepository.findById(userId).orElseThrow();

            bumpTokenVersion(userId);

            user.setFullName("Changed Name");
            userRepository.saveAndFlush(user);
            return null;
        });

        assertThat(columnInDatabase(userId, "full_name")).isEqualTo("Changed Name");
        assertThat(columnInDatabase(userId, "token_version")).isEqualTo("1");
        assertThat(columnInDatabase(userId, "status")).isEqualTo("ACTIVE");
        assertThat(columnInDatabase(userId, "role")).isEqualTo("STUDENT");
    }

    @Test
    @DisplayName("UC-04: two profiles edited at once keep both changes, because each write names "
            + "only its own column")
    void concurrentEditsToDifferentColumnsAreBothKept() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);
        Long userId = userIdOf(email);

        int attempts = 2;
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            Future<ResponseEntity<String>> nameEdit = pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return send(HttpMethod.PATCH, PROFILE_URL, token,
                        Map.of("fullName", "Concurrently Renamed"));
            });
            Future<ResponseEntity<String>> yearEdit = pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return send(HttpMethod.PATCH, PROFILE_URL, token,
                        Map.of("academicYear", "Year 2"));
            });

            assertThat(nameEdit.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(yearEdit.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            pool.shutdownNow();
        }

        assertThat(fullNameInDatabase(userId)).isEqualTo("Concurrently Renamed");
        assertThat(academicYearInDatabase(userId)).isEqualTo("Year 2");
    }

    @Test
    @DisplayName("UC-04/UC-27: an administrator token cannot read or write a student's profile")
    void anAdministratorTokenIsRefused() throws Exception {

        String adminToken = adminLogin();

        assertThat(send(HttpMethod.GET, PROFILE_URL, adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.PATCH, PROFILE_URL, adminToken, Map.of("fullName", "Admin"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.PATCH, PREFERENCES_URL, adminToken, Map.of("themePreference", "DARK"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("UC-04/VĐ-04: ck_users_money rejects a negative value at the database")
    void databaseCheckRejectsNegativeMoney() throws Exception {
        String email = randomEmail();
        register(email);
        Long userId = userIdOf(email);

        assertThatThrownBy(() -> {
            try (Connection connection = openDatabaseConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE users SET monthly_savings_goal = -1 WHERE id = ?")) {
                statement.setLong(1, userId);
                statement.executeUpdate();
            }
        }).isInstanceOf(SQLException.class)
          .hasMessageContaining("ck_users_money");

        assertThat(moneyInDatabase(userId, "monthly_savings_goal")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("UC-27: the theme_pref ENUM rejects a value outside its member list")
    void databaseRejectsUnknownTheme() throws Exception {
        String email = randomEmail();
        register(email);
        Long userId = userIdOf(email);

        assertThatThrownBy(() -> columnUpdate(userId, "theme_pref", "BLUE"))
                .isInstanceOf(SQLException.class);
    }

    private void assertFieldError(Map<String, ?> body, String expectedField) throws Exception {
        assertFieldError(PROFILE_URL, body, expectedField);
    }

    private void assertFieldError(String url, Map<String, ?> body, String expectedField)
            throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);

        ResponseEntity<String> response = send(HttpMethod.PATCH, url, token, body);

        assertThat(response.getStatusCode())
                .as("body=%s", body)
                .isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode parsed = objectMapper.readTree(response.getBody());
        assertThat(parsed.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");

        List<String> names = new ArrayList<>();
        parsed.get("fieldErrors").forEach(error -> names.add(error.get("field").asText()));

        assertThat(names).as("body=%s", body).contains(expectedField);
    }

    private void register(String email) {
        ResponseEntity<String> response = send(HttpMethod.POST, REGISTER_URL, null, Map.of(
                "fullName", "Test Student",
                "email", email,
                "password", PASSWORD,
                "confirmPassword", PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String login(String email) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, LOGIN_URL, null,
                Map.of("email", email, "password", PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    private String adminLogin() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/admin/auth/login", null,
                Map.of("email", SEEDED_ADMIN_EMAIL, "password", SEEDED_ADMIN_PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    private ResponseEntity<String> send(HttpMethod method, String url, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    private void setStatus(Long userId, String status) throws Exception {
        columnUpdate(userId, "status", status);
    }

    private void bumpTokenVersion(Long userId) {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE users SET token_version = token_version + 1 WHERE id = ?")) {
            statement.setLong(1, userId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not bump token_version", ex);
        }
    }

    private void columnUpdate(Long userId, String column, String value) throws Exception {

        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE users SET " + column + " = ? WHERE id = ?")) {
            statement.setString(1, value);
            statement.setLong(2, userId);
            statement.executeUpdate();
        }
    }

    private String columnInDatabase(Long userId, String column) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM users WHERE id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private String fullNameInDatabase(Long userId) throws Exception {
        return columnInDatabase(userId, "full_name");
    }

    private String academicYearInDatabase(Long userId) throws Exception {
        return columnInDatabase(userId, "academic_year");
    }

    private java.math.BigDecimal moneyInDatabase(Long userId, String column) throws Exception {
        return new java.math.BigDecimal(columnInDatabase(userId, column));
    }

    private Long userIdOf(String email) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private String randomEmail() {
        return "profile." + UUID.randomUUID() + "@student.campuscoin.edu";
    }
}
