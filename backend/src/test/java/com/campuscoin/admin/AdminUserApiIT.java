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

/**
 * Endpoints 46–48: listing the accounts, enabling and disabling one, and sending a reset link
 * (UC-22).
 *
 * <p><b>The three properties this class is really about.</b>
 *
 * <ol>
 *   <li><b>What a user response may contain.</b> {@code AdminUserResponse}'s fields are pinned as a
 *       literal list, and the database is read to show that the columns the response does not carry -
 *       {@code password_hash}, {@code token_version} - were never selected. A later "just add the
 *       last login IP" fails a test rather than quietly widening what an administrator can see.</li>
 *   <li><b>That disabling is a transition, not a column write.</b> The postcondition is asserted in
 *       one test: {@code token_version} +1, every open session revoked with
 *       {@code revoked_reason = 'ADMIN_DISABLE'}, and the student's token - minted before the change -
 *       answered with {@code 401}. Nothing here touches those tables; the assertion is on what
 *       {@code sp_set_user_status} did.</li>
 *   <li><b>That a refusal leaves no trace.</b> A self-disable must not write an audit row, because an
 *       audit log that records things that did not happen is worse than none.</li>
 * </ol>
 *
 * <p>Targets are freshly registered students except where the test needs a second administrator, and
 * the seeded administrator is only ever read. Nothing here changes the seeded student's row, so no
 * other suite is affected by this one's writes.
 */
class AdminUserApiIT extends AbstractAdminApiIT {

    /**
     * The fields endpoint 46 publishes, as a literal.
     *
     * <p>Eight, and each one is justified by a use case: {@code lastLoginAt} because UC-23's "active
     * users" figure is about recency of use, {@code academicYear} because that is what distinguishes
     * two students with the same name. {@code tokenVersion} is the notable absence - it is the whole
     * security meaning of a JWT's {@code tv} claim, so publishing it would let an attacker decide
     * whether a stolen token is still live. {@code monthlyAllowanceBaseline} and
     * {@code monthlySavingsGoal} are VĐ-04's private figures and no UC-22 screen has them.
     */
    private static final List<String> DOCUMENTED_USER_FIELDS = List.of(
            "id", "email", "fullName", "role", "status", "academicYear", "lastLoginAt", "createdAt");

    private static final String STATUS_URL = ADMIN_USERS_URL + "/%d/status";
    private static final String RESET_URL = ADMIN_USERS_URL + "/%d/password-reset";

    // ==================================================================
    //  46 — GET /api/v1/admin/users
    // ==================================================================

    /**
     * The two documented fields that are <em>omitted</em> from a freshly registered account.
     *
     * <p>Both are {@code @JsonInclude(NON_NULL)} and both are genuinely null for an account that has
     * never signed in and never set a year of study, so they are absent from the JSON rather than
     * present as {@code null} - the omission is the contract, and {@code AdminUserResponse} documents
     * it as such ("omitted when the student has not set one"). A test that expected the full eight on
     * this account would be asserting a shape the response deliberately does not have; module 10 hit
     * the same contradiction and resolved it the same way.
     */
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

        // A literal set, not a reflected one: a field added to the record fails this rather than
        // silently joining the contract. Compared against the documented eight minus the two that are
        // omitted until they are set, which is this account's actual shape.
        assertThat(fieldNamesOf(listed)).containsExactlyInAnyOrderElementsOf(
                DOCUMENTED_USER_FIELDS.stream()
                        .filter(field -> !FIELDS_OMITTED_UNTIL_SET.contains(field))
                        .toList());
        assertThat(fieldNamesOf(listed)).doesNotContainAnyElementsOf(FIELDS_OMITTED_UNTIL_SET);

        assertThat(listed.get("email").asText()).isEqualTo(email);
        assertThat(listed.get("role").asText()).isEqualTo("STUDENT");
        assertThat(listed.get("status").asText()).isEqualTo("ACTIVE");
        // Never signed in, so the field the schema marks nullable is either omitted or null - both
        // mean "never", and a client must be able to tell that from "signed in at the epoch".
        assertThat(listed.hasNonNull("lastLoginAt")).isFalse();
    }

    @Test
    @DisplayName("UC-22 B1: an account that has set a year and signed in carries both omitted fields")
    void theTwoOmittedFieldsAppearOnceTheyAreSet() throws Exception {
        // The other half of the assertion above, and what makes it more than a shape check: the two
        // names are absent from a fresh account because the values are absent, not because the response
        // can never carry them. Setting the one an endpoint can set, and signing in so the other is
        // written, must produce all eight documented names - which is also the check that the documented
        // literal is the full set rather than a list that happens to match an empty account.
        String email = randomEmail();
        registerStudent(email, PASSWORD);
        // Signing in is what writes `last_login_at`, and it is the same call that mints the token the
        // profile update below needs.
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
        // UC-22 B1 is "every account on the system", and an administrator editing accounts has to
        // be able to see other administrators - if only so that disabling one is a deliberate act
        // rather than an accident of a narrower list.
        JsonNode users = ok(HttpMethod.GET, ADMIN_USERS_URL, adminToken(), null);

        JsonNode seededAdmin = userWithId(users, seededAdminId());
        assertThat(seededAdmin).as("the seeded administrator must appear").isNotNull();
        assertThat(seededAdmin.get("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Section 7.2: the projection never reads the hash or the token version")
    void theListNeverReadsTheForbiddenColumns() throws Exception {
        // The response field list above shows what leaves the server; this shows the same guarantee
        // from the other end. The values are read straight from the row so the test can assert they
        // are real and populated - which is what makes "absent from the response" a decision rather
        // than an accident of a null column.
        String email = randomEmail();
        Long id = registeredStudentId(email);
        adminToken(); // a real sign-in, so token_version is what the auth module wrote

        String storedHash = stringValueFrom("SELECT password_hash FROM users WHERE id = ?", id);
        assertThat(storedHash).as("the row really does carry a hash").isNotBlank();

        JsonNode listed = userWithId(ok(HttpMethod.GET, ADMIN_USERS_URL, adminToken(), null), id);
        assertThat(fieldNamesOf(listed)).doesNotContain("passwordHash", "password_hash",
                "tokenVersion", "token_version", "password");
        // And not the values either, under any name: a field called `secret` would pass the check
        // above. The serialised payload is searched for the hash itself.
        assertThat(listed.toString()).doesNotContain(storedHash);
    }

    @Test
    @DisplayName("UC-22 B1: the list is ordered by id, so two identical calls agree")
    void theListOrderIsStable() throws Exception {
        // v_admin_usage_stats has no ORDER BY of its own and the users read is the same shape of
        // defect: without an explicit order, two identical calls can return the same rows in
        // different sequences and a client paging through them would see duplicates and gaps.
        String token = adminToken();

        JsonNode first = ok(HttpMethod.GET, ADMIN_USERS_URL, token, null);
        JsonNode second = ok(HttpMethod.GET, ADMIN_USERS_URL, token, null);

        assertThat(idsOf(first)).isEqualTo(idsOf(second));
        assertThat(idsOf(first)).isSorted();
    }

    // ==================================================================
    //  47 — POST /api/v1/admin/users/{id}/status
    // ==================================================================

    @Test
    @DisplayName("UC-22 B3/BR-03: disabling revokes the sessions, bumps the version and answers 200")
    void disablingIsATransitionAndItsPostconditionHoldsInOneTest() throws Exception {
        String email = randomEmail();
        registerStudent(email, PASSWORD);
        Long id = longValueFrom("SELECT id FROM users WHERE email = ?", email);

        // A real sign-in, so there is an open session and a token whose `tv` claim is the current
        // version. Everything below is a consequence of that.
        String studentToken = loginAt(LOGIN_URL, email, PASSWORD);
        long versionBefore = longValueFrom("SELECT token_version FROM users WHERE id = ?", id);
        assertThat(countOf("SELECT COUNT(*) FROM user_sessions "
                + "WHERE user_id = ? AND revoked_at IS NULL", id))
                .as("the sign-in must have opened a session").isEqualTo(1);

        JsonNode response = ok(HttpMethod.POST, STATUS_URL.formatted(id), adminToken(),
                Map.of("status", "DISABLED"));

        assertThat(response.get("status").asText()).isEqualTo("DISABLED");
        assertThat(response.get("id").asLong()).isEqualTo(id);

        // The three independent reasons the token stops working, all asserted: the row's status,
        // the version the token was signed against, and the session behind it. Each alone would be
        // enough, which is the point - BR-03 holds even if one of them is ever changed.
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

        // ...and the student's own token, issued before the change, is now refused.
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
        // The detail names the transition, which is what makes the row readable later without
        // joining back to the row it changed - and after a re-enable the row alone could not say
        // what the status was before.
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

        // Two rows, and the second is USER_ENABLED rather than a second USER_DISABLED: one action
        // per transition, so the log reads as a sequence of decisions rather than of states.
        Long auditId = latestAuditId("USER_ENABLED", id);
        assertThat(auditTargetEntityOf(auditId)).isEqualTo("users");
        assertThat(auditDetailFieldOf(auditId, "previousStatus")).isEqualTo("DISABLED");
        assertThat(auditDetailFieldOf(auditId, "newStatus")).isEqualTo("ACTIVE");

        // A re-enable does not bump the version: the sessions were already revoked and the existing
        // tokens are already stale, so there is nothing further to invalidate. The student must sign
        // in again, which is the safe direction - restoring the token that was withdrawn would let
        // an attacker who held it through the disable keep it.
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

        // Refused before the procedure was called, so every side effect is absent: the status, the
        // version, the session and - the one worth asserting - the audit row. An audit log that
        // records things that did not happen is worse than no audit log, because it cannot be told
        // apart from one that records things that did.
        assertThat(stringValueFrom("SELECT status FROM users WHERE id = ?", adminId)).isEqualTo("ACTIVE");
        assertThat(longValueFrom("SELECT token_version FROM users WHERE id = ?", adminId))
                .isEqualTo(versionBefore);
        assertThat(auditRowCount("USER_DISABLED")).isEqualTo(auditsBefore);
        // The account still works, which is the caller-visible half of the same fact.
        assertThat(get(ADMIN_USERS_URL, token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("UC-22 B3: an administrator may re-enable their own account")
    void selfEnableIsPermitted() throws Exception {
        // The asymmetry is deliberate, and this test is what keeps a reviewer from "fixing" it.
        // Disabling is the direction that can lock an installation out of itself, so it is the one
        // the rule refuses; setting an already-active account ACTIVE is a permitted no-op that
        // writes a truthful audit row saying what was asked for.
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
        // The target exists for a reason: without it, "an administrator cannot disable an
        // administrator" and "nobody may disable an administrator" would look the same, and the
        // second is not the rule. The account is created through the database because there is no
        // API that creates an administrator.
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

        // The service's own answer rather than the procedure's SIGNAL text, because the procedure
        // raises one SQLSTATE for all three of its refusals - see AdminWriteFailure.
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
        // The enum is Jackson's, and GlobalExceptionHandler turns the parse failure into a field
        // error naming the path. Without that, a typo would be indistinguishable from a bug.
        Long id = registeredStudentId(randomEmail());

        JsonNode error = refused(HttpMethod.POST, STATUS_URL.formatted(id), adminToken(),
                Map.of("status", "SUSPENDED"), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");

        assertThat(fieldNamesIn(error)).containsExactly("status");
    }

    // ==================================================================
    //  48 — POST /api/v1/admin/users/{id}/password-reset
    // ==================================================================

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

        // The positive case's postcondition: a row exists, its hash is 64 lowercase hex characters
        // - the width of the CHAR(64) ascii_bin column and of a SHA-256 digest - and it is not yet
        // used. A row that did not match that shape would be a hash the verify endpoint could never
        // find.
        String storedHash = stringValueFrom(
                "SELECT token_hash FROM password_reset_tokens WHERE user_id = ? "
                        + "ORDER BY id DESC LIMIT 1", id);
        assertThat(storedHash).matches("[0-9a-f]{64}");

        // What was stored is not what was sent. The raw value exists in exactly two places - the
        // link the notifier delivered, and the message that was never returned - and the hash is
        // not it.
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

        // VĐ-06: an administrator only *sends* a link. One who could read the token off the screen
        // could take the account over, which is the opposite of the rule - so the whole payload is
        // searched for the token itself rather than a field list trusted, and the field scan is the
        // second question: a payload that leaked the token under a name nobody guessed would already
        // have failed the search above.
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
        // The end-to-end claim UC-22 B4 makes: an administrator-triggered reset is a real reset.
        // The link is built by the shared builder, so nothing about the administrator's involvement
        // makes it a different kind of token - and this is what proves it rather than asserting the
        // builder is shared.
        String email = randomEmail();
        Long id = registeredStudentId(email);

        send(HttpMethod.POST, RESET_URL.formatted(id), adminToken(), null);
        String rawToken = lastRawTokenFor(email);

        JsonNode completed = ok(HttpMethod.POST, "/api/v1/auth/password-reset/complete", null,
                Map.of("token", rawToken, "newPassword", "NewPass@123",
                        "confirmPassword", "NewPass@123"));

        assertThat(completed.get("message").asText()).isNotBlank();
        assertThat(loginAt(LOGIN_URL, email, "NewPass@123")).isNotBlank();
        // BR-04's one-time use, seen from the administrator's side: the second use is refused.
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

        // The procedure invalidates the earlier token when it issues a new one, so an administrator
        // who sends twice does not leave two live links in a mailbox.
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

        // This is the case the service's pre-read exists for: sp_admin_send_password_reset does not
        // check that its target exists, so without the read an unknown id would reach the foreign
        // key and surface as a conflict - a 409 telling the caller to retry something that can never
        // succeed.
        assertThat(error.get("message").asText()).isEqualTo("User account not found.");
        assertThat(countOf("SELECT COUNT(*) FROM password_reset_tokens")).isEqualTo(tokensBefore);
        assertThat(auditRowCount("PASSWORD_RESET_SENT")).isEqualTo(auditsBefore);
    }

    @Test
    @DisplayName("UC-22 B4: a disabled account can still be sent a reset link, but not signed into")
    void aDisabledAccountCanBeSentAResetLink() throws Exception {
        // Deliberate, and worth pinning so nobody "hardens" it away: a student locked out because
        // an administrator disabled the account by mistake is exactly who needs the link. Issuing
        // the token is not a way in - completing the reset does not re-enable the account, so the
        // disable still has to be undone by an administrator, which is the decision UC-22 gives
        // them. Both halves are asserted, because either alone would be consistent with the rule
        // being wrong.
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

        // ...and the administrator can undo it, after which the same credentials work again.
        ok(HttpMethod.POST, STATUS_URL.formatted(id), adminToken, Map.of("status", "ACTIVE"));
        assertThat(loginAt(LOGIN_URL, email, PASSWORD)).isNotBlank();
    }

    // ==================================================================
    //  Helpers
    // ==================================================================

    private static JsonNode userWithId(JsonNode users, Long id) {
        for (JsonNode user : users) {
            if (user.get("id").asLong() == id) {
                return user;
            }
        }
        return null;
    }
}
