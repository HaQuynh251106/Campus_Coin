package com.campuscoin.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

class AdminUserApiIT extends AbstractAdminApiIT {

    private static final List<String> DOCUMENTED_USER_FIELDS = List.of(
            "id", "email", "fullName", "role", "status", "academicYear", "lastLoginAt", "createdAt");

    private static final String STATUS_URL = ADMIN_USERS_URL + "/%d/status";
    private static final String RESET_URL = ADMIN_USERS_URL + "/%d/password-reset";

    private static final List<String> FIELDS_OMITTED_UNTIL_SET = List.of("academicYear", "lastLoginAt");

    @Test
    @DisplayName("UC-22 B1: the list describes every account with exactly the published fields")
    void theListPublishesExactlyTheDocumentedFields() throws Exception {
        String email = randomEmail();
        Long id = registeredStudentId(email);

        JsonNode users = ok(HttpMethod.GET, ADMIN_USERS_URL, adminToken(), null);

        assertThat(users.isArray()).isTrue();
        JsonNode listed = userWithId(users, id);
        assertThat(listed).as("the account just registered must appear").isNotNull();

        assertThat(fieldNamesOf(listed)).containsExactlyInAnyOrderElementsOf(
                DOCUMENTED_USER_FIELDS.stream()
                        .filter(field -> !FIELDS_OMITTED_UNTIL_SET.contains(field))
                        .toList());
        assertThat(fieldNamesOf(listed)).doesNotContainAnyElementsOf(FIELDS_OMITTED_UNTIL_SET);

        assertThat(listed.get("email").asText()).isEqualTo(email);
        assertThat(listed.get("role").asText()).isEqualTo("STUDENT");
        assertThat(listed.get("status").asText()).isEqualTo("ACTIVE");

        assertThat(listed.hasNonNull("lastLoginAt")).isFalse();
    }

    @Test
    @DisplayName("UC-22 B1: an account that has set a year and signed in carries both omitted fields")
    void theTwoOmittedFieldsAppearOnceTheyAreSet() throws Exception {

        String email = randomEmail();
        registerStudent(email, PASSWORD);

        String studentToken = loginAt(LOGIN_URL, email, PASSWORD);
        patched(PROFILE_URL, studentToken, Map.of("academicYear", "Year 3"));

        JsonNode listed = userWithId(ok(HttpMethod.GET, ADMIN_USERS_URL, adminToken(), null),
                userIdOf(studentToken));

        assertThat(listed).isNotNull();
        assertThat(fieldNamesOf(listed)).containsExactlyInAnyOrderElementsOf(DOCUMENTED_USER_FIELDS);
        assertThat(listed.get("academicYear").asText()).isEqualTo("Year 3");
        assertThat(listed.hasNonNull("lastLoginAt")).isTrue();
    }

    @Test
    @DisplayName("UC-22 B1: the list includes administrators as well as students")
    void theListIncludesAdministrators() throws Exception {

        JsonNode users = ok(HttpMethod.GET, ADMIN_USERS_URL, adminToken(), null);

        JsonNode seededAdmin = userWithId(users, seededAdminId());
        assertThat(seededAdmin).as("the seeded administrator must appear").isNotNull();
        assertThat(seededAdmin.get("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Section 7.2: the projection never reads the hash or the token version")
    void theListNeverReadsTheForbiddenColumns() throws Exception {

        String email = randomEmail();
        Long id = registeredStudentId(email);
        adminToken();

        String storedHash = stringValueFrom("SELECT password_hash FROM users WHERE id = ?", id);
        assertThat(storedHash).as("the row really does carry a hash").isNotBlank();

        JsonNode listed = userWithId(ok(HttpMethod.GET, ADMIN_USERS_URL, adminToken(), null), id);
        assertThat(fieldNamesOf(listed)).doesNotContain("passwordHash", "password_hash",
                "tokenVersion", "token_version", "password");

        assertThat(listed.toString()).doesNotContain(storedHash);
    }

    @Test
    @DisplayName("UC-22 B1: the list is ordered by id, so two identical calls agree")
    void theListOrderIsStable() throws Exception {

        String token = adminToken();

        JsonNode first = ok(HttpMethod.GET, ADMIN_USERS_URL, token, null);
        JsonNode second = ok(HttpMethod.GET, ADMIN_USERS_URL, token, null);

        assertThat(idsOf(first)).isEqualTo(idsOf(second));
        assertThat(idsOf(first)).isSorted();
    }

    @Test
    @DisplayName("UC-22 B3/BR-03: disabling revokes the sessions, bumps the version and answers 200")
    void disablingIsATransitionAndItsPostconditionHoldsInOneTest() throws Exception {
        String email = randomEmail();
        registerStudent(email, PASSWORD);
        Long id = longValueFrom("SELECT id FROM users WHERE email = ?", email);

        String studentToken = loginAt(LOGIN_URL, email, PASSWORD);
        long versionBefore = longValueFrom("SELECT token_version FROM users WHERE id = ?", id);
        assertThat(countOf("SELECT COUNT(*) FROM user_sessions "
                + "WHERE user_id = ? AND revoked_at IS NULL", id))
                .as("the sign-in must have opened a session").isEqualTo(1);

        JsonNode response = ok(HttpMethod.POST, STATUS_URL.formatted(id), adminToken(),
                Map.of("status", "DISABLED"));

        assertThat(response.get("status").asText()).isEqualTo("DISABLED");
        assertThat(response.get("id").asLong()).isEqualTo(id);

        assertThat(stringValueFrom("SELECT status FROM users WHERE id = ?", id)).isEqualTo("DISABLED");
        assertThat(longValueFrom("SELECT token_version FROM users WHERE id = ?", id))
                .as("disabling bumps token_version so every JWT already issued is stale")
                .isEqualTo(versionBefore + 1);
        assertThat(countOf("SELECT COUNT(*) FROM user_sessions WHERE user_id = ? "
                + "AND revoked_at IS NOT NULL AND revoked_reason = 'ADMIN_DISABLE'", id))
                .as("every open session is revoked, with the reason recorded")
                .isEqualTo(1);
        assertThat(countOf("SELECT COUNT(*) FROM user_sessions WHERE user_id = ? "
                + "AND revoked_at IS NULL", id))
                .as("no session is left open")
                .isZero();

        assertThat(get(PROFILE_URL, studentToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UC-22 B3: the audit row records the actor, the target and both statuses")
    void disablingWritesTheDocumentedAuditRow() throws Exception {
        String email = randomEmail();
        Long targetId = registeredStudentId(email);

        ok(HttpMethod.POST, STATUS_URL.formatted(targetId), adminToken(), Map.of("status", "DISABLED"));

        Long auditId = latestAuditId("USER_DISABLED", targetId);
        assertThat(auditTargetEntityOf(auditId)).isEqualTo("users");
        assertThat(auditActorOf(auditId)).isEqualTo(seededAdminId());

        assertThat(auditDetailFieldOf(auditId, "previousStatus")).isEqualTo("ACTIVE");
        assertThat(auditDetailFieldOf(auditId, "newStatus")).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("UC-22 B3: re-enabling restores access and is recorded as its own action")
    void enablingIsRecordedUnderItsOwnAction() throws Exception {
        String email = randomEmail();
        registerStudent(email, PASSWORD);
        Long id = longValueFrom("SELECT id FROM users WHERE email = ?", email);
        String token = adminToken();

        ok(HttpMethod.POST, STATUS_URL.formatted(id), token, Map.of("status", "DISABLED"));
        long versionAfterDisable = longValueFrom("SELECT token_version FROM users WHERE id = ?", id);

        JsonNode response = ok(HttpMethod.POST, STATUS_URL.formatted(id), token,
                Map.of("status", "ACTIVE"));

        assertThat(response.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(stringValueFrom("SELECT status FROM users WHERE id = ?", id)).isEqualTo("ACTIVE");

        Long auditId = latestAuditId("USER_ENABLED", id);
        assertThat(auditTargetEntityOf(auditId)).isEqualTo("users");
        assertThat(auditDetailFieldOf(auditId, "previousStatus")).isEqualTo("DISABLED");
        assertThat(auditDetailFieldOf(auditId, "newStatus")).isEqualTo("ACTIVE");

        assertThat(longValueFrom("SELECT token_version FROM users WHERE id = ?", id))
                .isEqualTo(versionAfterDisable);
        assertThat(loginAt(LOGIN_URL, email, PASSWORD)).isNotBlank();
    }

    @Test
    @DisplayName("UC-22 A1: an administrator cannot disable their own account, and nothing is written")
    void selfDisableIsRefusedWithoutLeavingATrace() throws Exception {
        String token = adminToken();
        Long adminId = seededAdminId();
        long versionBefore = longValueFrom("SELECT token_version FROM users WHERE id = ?", adminId);
        int auditsBefore = auditRowCount("USER_DISABLED");

        JsonNode error = refused(HttpMethod.POST, STATUS_URL.formatted(adminId), token,
                Map.of("status", "DISABLED"), HttpStatus.CONFLICT, "SELF_DISABLE_FORBIDDEN");

        assertThat(error.get("message").asText()).contains("cannot disable your own account");

        assertThat(stringValueFrom("SELECT status FROM users WHERE id = ?", adminId)).isEqualTo("ACTIVE");
        assertThat(longValueFrom("SELECT token_version FROM users WHERE id = ?", adminId))
                .isEqualTo(versionBefore);
        assertThat(auditRowCount("USER_DISABLED")).isEqualTo(auditsBefore);

        assertThat(get(ADMIN_USERS_URL, token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("UC-22 B3: an administrator may re-enable their own account")
    void selfEnableIsPermitted() throws Exception {

        Long adminId = seededAdminId();

        JsonNode response = ok(HttpMethod.POST, STATUS_URL.formatted(adminId), adminToken(),
                Map.of("status", "ACTIVE"));

        assertThat(response.get("id").asLong()).isEqualTo(adminId);
        assertThat(stringValueFrom("SELECT status FROM users WHERE id = ?", adminId)).isEqualTo("ACTIVE");
        assertThat(auditTargetEntityOf(latestAuditId("USER_ENABLED", adminId))).isEqualTo("users");
    }

    @Test
    @DisplayName("BR-02: another administrator can disable an administrator")
    void anotherAdministratorCanDisableAnAdministrator() throws Exception {

        String targetEmail = randomEmail();
        Long targetId = secondAdmin(targetEmail);
        String targetToken = loginAt(ADMIN_LOGIN_URL, targetEmail, SEEDED_ADMIN_PASSWORD);

        assertThat(get(ADMIN_USERS_URL, targetToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        ok(HttpMethod.POST, STATUS_URL.formatted(targetId), adminToken(), Map.of("status", "DISABLED"));

        assertThat(stringValueFrom("SELECT status FROM users WHERE id = ?", targetId))
                .isEqualTo("DISABLED");
        assertThat(get(ADMIN_USERS_URL, targetToken).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(auditActorOf(latestAuditId("USER_DISABLED", targetId))).isEqualTo(seededAdminId());
    }

    @Test
    @DisplayName("UC-22 B3: an unknown id is a 404 that names the account, not the procedure")
    void anUnknownAccountIsNotFound() throws Exception {
        Long unknownId = noSuchIdIn("users");
        int auditsBefore = auditRowCount("USER_DISABLED");

        JsonNode error = refused(HttpMethod.POST, STATUS_URL.formatted(unknownId), adminToken(),
                Map.of("status", "DISABLED"), HttpStatus.NOT_FOUND, "NOT_FOUND");

        assertThat(error.get("message").asText()).isEqualTo("User account not found.");
        assertThat(auditRowCount("USER_DISABLED"))
                .as("nothing was disabled, so nothing is recorded")
                .isEqualTo(auditsBefore);
    }

    @Test
    @DisplayName("Section 19: a body the DTO refuses is a field error on the field that is wrong")
    void aMissingStatusIsAFieldError() throws Exception {
        Long id = registeredStudentId(randomEmail());

        JsonNode error = refused(HttpMethod.POST, STATUS_URL.formatted(id), adminToken(),
                Map.of(), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");

        assertThat(fieldNamesIn(error)).containsExactly("status");
    }

    @Test
    @DisplayName("Section 19: an unrecognised status is a field error rather than a server error")
    void anUnknownStatusIsAFieldError() throws Exception {

        Long id = registeredStudentId(randomEmail());

        JsonNode error = refused(HttpMethod.POST, STATUS_URL.formatted(id), adminToken(),
                Map.of("status", "SUSPENDED"), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");

        assertThat(fieldNamesIn(error)).containsExactly("status");
    }

    @Test
    @DisplayName("UC-22 B4: a reset link is issued, and the raw token reaches only the notifier")
    void sendingAResetLinkStoresOnlyTheHashAndNeverReturnsTheToken() throws Exception {
        String email = randomEmail();
        Long id = registeredStudentId(email);

        var response = send(HttpMethod.POST, RESET_URL.formatted(id), adminToken(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = body(response);
        assertThat(fieldNamesOf(body)).containsExactly("message");
        assertThat(body.get("message").asText())
                .isEqualTo("A password reset link has been sent to the account's email address.");

        String storedHash = stringValueFrom(
                "SELECT token_hash FROM password_reset_tokens WHERE user_id = ? "
                        + "ORDER BY id DESC LIMIT 1", id);
        assertThat(storedHash).matches("[0-9a-f]{64}");

        String rawToken = lastRawTokenFor(email);
        assertThat(rawToken).isNotEqualTo(storedHash);
        assertThat(body.toString()).doesNotContain(rawToken);
        assertThat(storedHash).isEqualTo(tokenHashService.sha256Hex(rawToken));
    }

    @Test
    @DisplayName("UC-22 B4/VĐ-06: the response carries nothing an administrator could sign in with")
    void theResetResponseCarriesNoTokenOnAnyField() throws Exception {
        String email = randomEmail();
        Long id = registeredStudentId(email);

        JsonNode body = body(send(HttpMethod.POST, RESET_URL.formatted(id), adminToken(), null));

        assertThat(body.toString()).doesNotContain(lastRawTokenFor(email));
        assertThat(fieldNamesOf(body)).doesNotContain("token", "tokenHash", "token_hash", "resetToken",
                "reset_token", "link", "url", "expiresAt", "expires_at");
    }

    @Test
    @DisplayName("UC-22 B4: the audit row records the actor, the target and the channel")
    void sendingAResetLinkWritesTheDocumentedAuditRow() throws Exception {
        String email = randomEmail();
        Long id = registeredStudentId(email);
        Long adminId = seededAdminId();

        send(HttpMethod.POST, RESET_URL.formatted(id), adminToken(), null);

        Long auditId = latestAuditId("PASSWORD_RESET_SENT", id);
        assertThat(auditTargetEntityOf(auditId)).isEqualTo("users");
        assertThat(auditActorOf(auditId)).isEqualTo(adminId);
        assertThat(auditDetailFieldOf(auditId, "channel")).isEqualTo("email");
    }

    @Test
    @DisplayName("UC-22 B4: the token is one the student's own completion endpoint accepts")
    void theIssuedTokenIsAcceptedByTheStudentCompletionEndpoint() throws Exception {

        String email = randomEmail();
        Long id = registeredStudentId(email);

        send(HttpMethod.POST, RESET_URL.formatted(id), adminToken(), null);
        String rawToken = lastRawTokenFor(email);

        JsonNode completed = ok(HttpMethod.POST, "/api/v1/auth/password-reset/complete", null,
                Map.of("token", rawToken, "newPassword", "NewPass@123",
                        "confirmPassword", "NewPass@123"));

        assertThat(completed.get("message").asText()).isNotBlank();
        assertThat(loginAt(LOGIN_URL, email, "NewPass@123")).isNotBlank();

        assertThat(send(HttpMethod.POST, "/api/v1/auth/password-reset/complete", null,
                Map.of("token", rawToken, "newPassword", "Another@123",
                        "confirmPassword", "Another@123")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("UC-22 B4: sending a second link invalidates the first (BR-04)")
    void aSecondLinkInvalidatesTheFirst() throws Exception {
        String email = randomEmail();
        Long id = registeredStudentId(email);
        String adminToken = adminToken();

        send(HttpMethod.POST, RESET_URL.formatted(id), adminToken, null);
        String firstToken = lastRawTokenFor(email);
        send(HttpMethod.POST, RESET_URL.formatted(id), adminToken, null);
        String secondToken = lastRawTokenFor(email);

        assertThat(secondToken).isNotEqualTo(firstToken);

        assertThat(send(HttpMethod.POST, "/api/v1/auth/password-reset/complete", null,
                Map.of("token", firstToken, "newPassword", "NewPass@123",
                        "confirmPassword", "NewPass@123")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(countOf("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ? "
                + "AND used_at IS NULL AND expires_at > NOW()", id))
                .as("exactly one live token remains")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("UC-22 B4: an unknown id is a 404 with no token row and no audit row")
    void anUnknownAccountGetsNoResetLink() throws Exception {
        int auditsBefore = auditRowCount("PASSWORD_RESET_SENT");
        int tokensBefore = countOf("SELECT COUNT(*) FROM password_reset_tokens");

        JsonNode error = refused(HttpMethod.POST, RESET_URL.formatted(noSuchIdIn("users")),
                adminToken(), null, HttpStatus.NOT_FOUND, "NOT_FOUND");

        assertThat(error.get("message").asText()).isEqualTo("User account not found.");
        assertThat(countOf("SELECT COUNT(*) FROM password_reset_tokens")).isEqualTo(tokensBefore);
        assertThat(auditRowCount("PASSWORD_RESET_SENT")).isEqualTo(auditsBefore);
    }

    @Test
    @DisplayName("UC-22 B4: a disabled account can still be sent a reset link, but not signed into")
    void aDisabledAccountCanBeSentAResetLink() throws Exception {

        String email = randomEmail();
        Long id = registeredStudentId(email);
        String adminToken = adminToken();

        ok(HttpMethod.POST, STATUS_URL.formatted(id), adminToken, Map.of("status", "DISABLED"));

        assertThat(send(HttpMethod.POST, RESET_URL.formatted(id), adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(countOf("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ? "
                + "AND used_at IS NULL AND expires_at > NOW()", id))
                .as("a live link was issued to a disabled account")
                .isEqualTo(1);

        ResponseEntity<String> refusedLogin = send(HttpMethod.POST, LOGIN_URL, null,
                Map.of("email", email, "password", PASSWORD));
        assertThat(refusedLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCodeOf(refusedLogin))
                .as("the account is disabled, not the password wrong")
                .isEqualTo("ACCOUNT_DISABLED");

        ok(HttpMethod.POST, STATUS_URL.formatted(id), adminToken, Map.of("status", "ACTIVE"));
        assertThat(loginAt(LOGIN_URL, email, PASSWORD)).isNotBlank();
    }

    private static JsonNode userWithId(JsonNode users, Long id) {
        for (JsonNode user : users) {
            if (user.get("id").asLong() == id) {
                return user;
            }
        }
        return null;
    }
}
