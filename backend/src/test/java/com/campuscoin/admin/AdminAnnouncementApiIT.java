package com.campuscoin.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Endpoints 52–54: the system announcements (UC-21 B1, B2).
 *
 * <p><b>The test that matters most here is a comparison, not a property.</b> An administration screen
 * and a student's dashboard both show announcements and must show different ones: the dashboard shows
 * what is live and addressed to students, the administrator shows everything in order to be able to
 * switch any of it back on. A single announcement can therefore be asserted twice - present in 52,
 * absent from the dashboard - and that pair is what proves 52 is not a second route onto
 * {@code v_active_announcements}. Each absence on its own would be consistent with the row having
 * failed to be created at all.
 *
 * <p><b>Content is create-once.</b> 54 moves {@code is_active} and nothing else, so a test POSTs an
 * announcement, PATCHes it, and asserts the title and body are byte-for-byte what they were. The
 * refusal that proves the rule from the other side is the window check: an end at or before the start
 * is a field error here, because {@code ck_ann_window} would otherwise report it as a constraint
 * violation naming a column nobody typed.
 */
class AdminAnnouncementApiIT extends AbstractAdminApiIT {

    private static final String ANNOUNCEMENT_ITEM_URL = ADMIN_ANNOUNCEMENTS_URL + "/%d";
    private static final String DASHBOARD_URL = "/api/v1/dashboard";

    /** The zone the API resolves its default start time in; the same one the JDBC session uses. */
    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * The fields an announcement response carries, as a literal.
     *
     * <p>Nine, and the absence worth naming is {@code createdBy}: who published a notice is recorded
     * in {@code admin_audit_log}, which is the record of who did what, so a per-row author field
     * would be a second and weaker answer to the same question. {@code endsAt} is genuinely optional
     * and omitted when absent, so a response without it is not a missing field.
     */
    private static final List<String> DOCUMENTED_FIELDS = List.of(
            "id", "title", "body", "severity", "audience", "startsAt", "endsAt", "isActive",
            "createdAt");

    // ==================================================================
    //  52 — GET /api/v1/admin/announcements
    // ==================================================================

    @Test
    @DisplayName("UC-21: the list shows what a student's dashboard hides, and vice versa")
    void theAdminListAndTheStudentDashboardDisagreeOnPurpose() throws Exception {
        // The pair of assertions this class exists for. One notice, addressed to nobody a student
        // is, switched off, and already over - three independent reasons the dashboard's view
        // excludes it and the administrator's screen must not.
        String token = adminToken();
        String title = uniqueTitle();
        JsonNode created = created(ADMIN_ANNOUNCEMENTS_URL, token, Map.of(
                "title", title,
                "body", "A notice only an administrator should be able to see.",
                "severity", "WARNING",
                "audience", "ADMINS",
                "startsAt", "2026-01-01T00:00:00",
                "endsAt", "2026-01-02T00:00:00"));
        Long id = created.get("id").asLong();
        patched(ANNOUNCEMENT_ITEM_URL.formatted(id), token, Map.of("isActive", false));

        // 52: present. An administrator has to be able to find a withdrawn notice in order to
        // restore it, and an expired ADMINS-only one is exactly the row a filtered list would lose.
        JsonNode listed = announcementWithId(
                ok(HttpMethod.GET, ADMIN_ANNOUNCEMENTS_URL, token, null), id);
        assertThat(listed).as("the administrator's list must include a withdrawn, expired notice")
                .isNotNull();
        assertThat(listed.get("audience").asText()).isEqualTo("ADMINS");
        assertThat(listed.get("isActive").asBoolean()).isFalse();

        // The dashboard: absent, and absent for three simultaneous reasons, which is why the same
        // row appears in one list and not the other rather than it being a matter of ordering.
        JsonNode dashboard = ok(HttpMethod.GET, DASHBOARD_URL, studentToken(), null);
        assertThat(titlesIn(dashboard.get("announcements"))).doesNotContain(title);
    }

    @Test
    @DisplayName("UC-21: a live student notice reaches both lists, so the pair above is not vacuous")
    void aLiveStudentNoticeReachesBothLists() throws Exception {
        // The non-triviality guard for the comparison above: without it, the dashboard assertion
        // would pass against an endpoint that returned an empty array for everybody.
        String title = uniqueTitle();
        JsonNode created = created(ADMIN_ANNOUNCEMENTS_URL, adminToken(), Map.of(
                "title", title,
                "body", "A notice every student should see.",
                "severity", "SUCCESS",
                "audience", "STUDENTS"));
        Long id = created.get("id").asLong();

        assertThat(titlesIn(ok(HttpMethod.GET, ADMIN_ANNOUNCEMENTS_URL, adminToken(), null)))
                .contains(title);

        JsonNode studentDashboard = ok(HttpMethod.GET, DASHBOARD_URL, studentToken(), null);
        assertThat(titlesIn(studentDashboard.get("announcements"))).contains(title);
    }

    @Test
    @DisplayName("UC-21: the list publishes exactly the documented fields and no author")
    void theListPublishesExactlyTheDocumentedFields() throws Exception {
        JsonNode announcements = ok(HttpMethod.GET, ADMIN_ANNOUNCEMENTS_URL, adminToken(), null);

        assertThat(announcements.isArray()).isTrue();
        assertThat(announcements).isNotEmpty();

        for (JsonNode announcement : announcements) {
            // A subset rather than an equality: `endsAt` is omitted when a notice is open-ended, and
            // the seeded notices all have one, so the two shapes are checked separately below.
            assertThat(fieldNamesOf(announcement)).isSubsetOf(DOCUMENTED_FIELDS);
            assertThat(fieldNamesOf(announcement))
                    .contains("id", "title", "body", "severity", "audience", "startsAt", "isActive",
                            "createdAt");
        }
        // No payload names its author, at any depth.
        assertThat(allKeysIn(announcements)).doesNotContain("createdBy", "created_by", "author");
    }

    @Test
    @DisplayName("UC-21: an open-ended notice omits its end time instead of sending null")
    void anOpenEndedNoticeOmitsItsEndTime() throws Exception {
        // `ends_at IS NULL` means "stays up until it is switched off", which the dashboard's view
        // expresses as `ends_at IS NULL OR ends_at >= NOW()`. The client distinguishes that from a
        // notice with a window, so the field has to be absent rather than null - the same convention
        // the profile module uses for its writable fields.
        JsonNode created = created(ADMIN_ANNOUNCEMENTS_URL, adminToken(), Map.of(
                "title", uniqueTitle(),
                "body", "No end date; it stays up until it is withdrawn.",
                "audience", "STUDENTS"));

        assertThat(fieldNamesOf(created)).doesNotContain("endsAt");
        assertThat(countOf("SELECT COUNT(*) FROM announcements WHERE id = ? AND ends_at IS NULL",
                created.get("id").asLong())).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-21: two identical calls return the same order, newest first")
    void theListOrderIsStableAndNewestFirst() throws Exception {
        String token = adminToken();
        String newest = uniqueTitle();
        Long newestId = created(ADMIN_ANNOUNCEMENTS_URL, token, Map.of(
                "title", newest, "body", "The most recently published notice.",
                "audience", "STUDENTS")).get("id").asLong();

        List<Long> first = idsOf(ok(HttpMethod.GET, ADMIN_ANNOUNCEMENTS_URL, token, null));
        List<Long> second = idsOf(ok(HttpMethod.GET, ADMIN_ANNOUNCEMENTS_URL, token, null));

        assertThat(first).isEqualTo(second);
        // `created_at` alone is not a total order - two notices published in the same second would
        // tie - so the DAO adds the id, and the newest row is the one just created.
        assertThat(first.get(0)).isEqualTo(newestId);
    }

    // ==================================================================
    //  53 — POST /api/v1/admin/announcements
    // ==================================================================

    @Test
    @DisplayName("UC-21 B1: the response is the row that was written, cross-checked against the audit row")
    void creatingReturnsTheRowThatWasCreated() throws Exception {
        // The read-back trap, from outside. `announcements` has no unique key, so the created row is
        // found by (author, title, start time) - and the title is unique here, so a service that
        // returned some other row would be caught. The audit row's `target_id` is the independent
        // witness: the procedure sets it from LAST_INSERT_ID() *internally*, before writing the
        // audit row, so it is the announcement's id and not the audit row's.
        String token = adminToken();
        String title = uniqueTitle();

        JsonNode response = created(ADMIN_ANNOUNCEMENTS_URL, token, Map.of(
                "title", title,
                "body", "The body of the notice the suite published.",
                "severity", "WARNING",
                "audience", "ALL",
                "startsAt", "2026-09-20T08:00:00",
                "endsAt", "2026-09-30T20:00:00"));

        Long id = response.get("id").asLong();
        assertThat(response.get("title").asText()).isEqualTo(title);
        assertThat(response.get("severity").asText()).isEqualTo("WARNING");
        assertThat(response.get("audience").asText()).isEqualTo("ALL");
        assertThat(response.get("startsAt").asText()).isEqualTo("2026-09-20T08:00:00");
        assertThat(response.get("isActive").asBoolean()).isTrue();

        Long auditId = latestAuditIdFor("ANNOUNCEMENT_CREATED", "announcements", id);
        assertThat(auditActorOf(auditId)).isEqualTo(seededAdminId());
        assertThat(auditDetailFieldOf(auditId, "title")).isEqualTo(title);
        assertThat(auditDetailFieldOf(auditId, "audience")).isEqualTo("ALL");
        assertThat(auditDetailFieldOf(auditId, "severity")).isEqualTo("WARNING");
    }

    @Test
    @DisplayName("UC-21 B1: an omitted severity, audience and start time take their documented defaults")
    void omittedFieldsTakeTheirDocumentedDefaults() throws Exception {
        // The defaults are `INFO`, `STUDENTS` and the current time, and `STUDENTS` in particular is
        // the safe direction: `ALL` must be asked for, so a client that forgets the field does not
        // broadcast to administrators.
        LocalDateTime before = LocalDateTime.now(APPLICATION_ZONE).minusMinutes(1);
        String title = uniqueTitle();

        JsonNode response = created(ADMIN_ANNOUNCEMENTS_URL, adminToken(), Map.of(
                "title", title,
                "body", "Only the two required fields are given."));

        assertThat(response.get("severity").asText()).isEqualTo("INFO");
        assertThat(response.get("audience").asText()).isEqualTo("STUDENTS");
        assertThat(response.get("isActive").asBoolean()).isTrue();
        // The start time the service supplied, which is what the read-back locates the row by.
        LocalDateTime startsAt = LocalDateTime.parse(response.get("startsAt").asText());
        assertThat(startsAt).isAfterOrEqualTo(before);
        assertThat(startsAt).isBeforeOrEqualTo(LocalDateTime.now(APPLICATION_ZONE).plusMinutes(1));
    }

    @Test
    @DisplayName("UC-21 B1: a required field left out is a field error on that field")
    void aMissingRequiredFieldIsAFieldError() throws Exception {
        String token = adminToken();

        JsonNode noTitle = refused(HttpMethod.POST, ADMIN_ANNOUNCEMENTS_URL, token,
                Map.of("body", "A body with no headline."),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNamesIn(noTitle)).containsExactly("title");

        JsonNode noBody = refused(HttpMethod.POST, ADMIN_ANNOUNCEMENTS_URL, token,
                Map.of("title", uniqueTitle()), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNamesIn(noBody)).containsExactly("body");
    }

    @Test
    @DisplayName("ck_ann_window: an end at or before the start is a field error, not a conflict")
    void anEndBeforeTheStartIsAFieldError() throws Exception {
        // Both of these are refused by `ck_ann_window`, which reports SQLSTATE 23000 and names the
        // constraint. Attaching that to the `endsAt` input is the difference between a client that
        // can mark the field and one that has to explain a database identifier to a user.
        String token = adminToken();
        int auditsBefore = auditRowCount("ANNOUNCEMENT_CREATED");

        JsonNode endBeforeStart = refused(HttpMethod.POST, ADMIN_ANNOUNCEMENTS_URL, token, Map.of(
                "title", uniqueTitle(),
                "body", "The window runs backwards.",
                "startsAt", "2026-09-30T12:00:00",
                "endsAt", "2026-09-29T12:00:00"), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNamesIn(endBeforeStart)).containsExactly("endsAt");

        // The boundary is strict: a window one instant long would never be seen, so an end *equal*
        // to the start is refused too. This is the case `isAfter` vs `!isBefore` gets wrong.
        JsonNode endAtStart = refused(HttpMethod.POST, ADMIN_ANNOUNCEMENTS_URL, token, Map.of(
                "title", uniqueTitle(),
                "body", "The window is one instant long.",
                "startsAt", "2026-09-30T12:00:00",
                "endsAt", "2026-09-30T12:00:00"), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNamesIn(endAtStart)).containsExactly("endsAt");

        // Neither refusal reached the procedure, so neither is recorded. That is the difference
        // between this and the 409 paths, where the database refused and the attempt is on record.
        assertThat(auditRowCount("ANNOUNCEMENT_CREATED"))
                .as("a request the API refused before the call leaves no audit row")
                .isEqualTo(auditsBefore);
    }

    @Test
    @DisplayName("UC-21 B1: an end exactly after the start is accepted - the boundary from the other side")
    void anEndJustAfterTheStartIsAccepted() throws Exception {
        // Without this, the test above would pass against a service that refused every window.
        JsonNode response = created(ADMIN_ANNOUNCEMENTS_URL, adminToken(), Map.of(
                "title", uniqueTitle(),
                "body", "One second long, and therefore visible for that second.",
                "startsAt", "2026-09-30T12:00:00",
                "endsAt", "2026-09-30T12:00:01"));

        assertThat(response.get("endsAt").asText()).isEqualTo("2026-09-30T12:00:01");
    }

    @Test
    @DisplayName("UC-21 B1: a title longer than the column is a field error")
    void anOverlongTitleIsAFieldError() throws Exception {
        // `title VARCHAR(150)`; the DTO refuses at the same width so the caller learns it from the
        // form rather than from a truncated row or a refused write.
        JsonNode error = refused(HttpMethod.POST, ADMIN_ANNOUNCEMENTS_URL, adminToken(), Map.of(
                "title", "T".repeat(151),
                "body", "A headline that is one character too long."),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");

        assertThat(fieldNamesIn(error)).containsExactly("title");
    }

    // ==================================================================
    //  54 — PATCH /api/v1/admin/announcements/{id}
    // ==================================================================

    @Test
    @DisplayName("UC-21 B2: deactivating moves one column and leaves the notice's text alone")
    void deactivatingChangesOnlyTheActiveFlag() throws Exception {
        // Content is create-once, and this is the test that says so: the row is read before and
        // after, and everything except `is_active` is compared. A PATCH that silently rewrote the
        // text - or that expected the client to send it back - would fail here.
        String token = adminToken();
        String title = uniqueTitle();
        String body = "The text of a notice that is about to be withdrawn.";
        Long id = created(ADMIN_ANNOUNCEMENTS_URL, token,
                Map.of("title", title, "body", body, "audience", "STUDENTS")).get("id").asLong();
        LocalDateTime startsAt = LocalDateTime.parse(stringValueFrom(
                "SELECT DATE_FORMAT(starts_at, '%Y-%m-%dT%H:%i:%s') FROM announcements WHERE id = ?",
                id));

        JsonNode response = patched(ANNOUNCEMENT_ITEM_URL.formatted(id), token,
                Map.of("isActive", false));

        assertThat(response.get("id").asLong()).isEqualTo(id);
        assertThat(response.get("isActive").asBoolean()).isFalse();
        assertThat(response.get("title").asText()).isEqualTo(title);
        assertThat(response.get("body").asText()).isEqualTo(body);
        assertThat(stringValueFrom("SELECT body FROM announcements WHERE id = ?", id)).isEqualTo(body);
        assertThat(LocalDateTime.parse(stringValueFrom(
                "SELECT DATE_FORMAT(starts_at, '%Y-%m-%dT%H:%i:%s') FROM announcements WHERE id = ?",
                id))).isEqualTo(startsAt);

        Long auditId = latestAuditIdFor("ANNOUNCEMENT_DEACTIVATED", "announcements", id);
        assertThat(auditActorOf(auditId)).isEqualTo(seededAdminId());
        assertThat(auditDetailFieldOf(auditId, "title")).isEqualTo(title);
        assertThat(auditDetailFieldOf(auditId, "isActive")).isEqualTo("0");
    }

    @Test
    @DisplayName("UC-21 B2: re-activating writes the other action's audit row")
    void reactivatingIsRecordedUnderItsOwnAction() throws Exception {
        // The activation and deactivation rows are distinguished by action, not by a boolean in the
        // detail, so a reader can count how often a notice was switched on without parsing JSON.
        // Both directions are asserted because one action string with a wrong branch would make the
        // log unreadable while every state assertion still passed.
        String token = adminToken();
        Long id = created(ADMIN_ANNOUNCEMENTS_URL, token, Map.of(
                "title", uniqueTitle(),
                "body", "A notice that will be switched off and on again.",
                "audience", "STUDENTS")).get("id").asLong();

        patched(ANNOUNCEMENT_ITEM_URL.formatted(id), token, Map.of("isActive", false));
        JsonNode reactivated = patched(ANNOUNCEMENT_ITEM_URL.formatted(id), token,
                Map.of("isActive", true));

        assertThat(reactivated.get("isActive").asBoolean()).isTrue();
        assertThat(auditDetailFieldOf(
                latestAuditIdFor("ANNOUNCEMENT_ACTIVATED", "announcements", id), "isActive"))
                .isEqualTo("1");
        // Both rows exist, which is the property a single mutable status column could not express.
        assertThat(countOf("SELECT COUNT(*) FROM admin_audit_log WHERE target_id = ? "
                + "AND action IN ('ANNOUNCEMENT_ACTIVATED', 'ANNOUNCEMENT_DEACTIVATED')", id))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("UC-21 B2: re-sending the current state is accepted and recorded")
    void resendingTheCurrentStateIsAccepted() throws Exception {
        // Deliberate rather than tolerated: the caller asked for the row to be inactive and it is,
        // so the request succeeded. Refusing it would make a client special-case an outcome it
        // cannot distinguish from a failure, and the audit row is a truthful record of the request.
        String token = adminToken();
        Long id = created(ADMIN_ANNOUNCEMENTS_URL, token, Map.of(
                "title", uniqueTitle(),
                "body", "A notice that is already inactive.",
                "audience", "STUDENTS")).get("id").asLong();

        patched(ANNOUNCEMENT_ITEM_URL.formatted(id), token, Map.of("isActive", false));
        JsonNode again = patched(ANNOUNCEMENT_ITEM_URL.formatted(id), token,
                Map.of("isActive", false));

        assertThat(again.get("isActive").asBoolean()).isFalse();
        assertThat(countOf("SELECT COUNT(*) FROM admin_audit_log WHERE target_id = ? "
                + "AND action = 'ANNOUNCEMENT_DEACTIVATED'", id))
                .as("each request leaves its own row")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("UC-21 B2: a withdrawn notice leaves the dashboard but stays in the admin list")
    void withdrawingHidesTheNoticeFromStudents() throws Exception {
        String token = adminToken();
        String title = uniqueTitle();
        Long id = created(ADMIN_ANNOUNCEMENTS_URL, token, Map.of(
                "title", title,
                "body", "Visible until it is withdrawn, then not.",
                "audience", "STUDENTS")).get("id").asLong();

        String studentToken = studentToken();
        assertThat(titlesIn(ok(HttpMethod.GET, DASHBOARD_URL, studentToken, null)
                .get("announcements"))).contains(title);

        patched(ANNOUNCEMENT_ITEM_URL.formatted(id), token, Map.of("isActive", false));

        assertThat(titlesIn(ok(HttpMethod.GET, DASHBOARD_URL, studentToken, null)
                .get("announcements")))
                .as("a withdrawn notice must disappear from the dashboard")
                .doesNotContain(title);
        assertThat(titlesIn(ok(HttpMethod.GET, ADMIN_ANNOUNCEMENTS_URL, token, null)))
                .as("...while remaining recoverable")
                .contains(title);
    }

    @Test
    @DisplayName("UC-21 B2: an unknown id is a 404 that names the announcement")
    void anUnknownAnnouncementIsNotFound() throws Exception {
        JsonNode error = refused(HttpMethod.PATCH,
                ANNOUNCEMENT_ITEM_URL.formatted(noSuchIdIn("announcements")), adminToken(),
                Map.of("isActive", false), HttpStatus.NOT_FOUND, "NOT_FOUND");

        // The service's answer, not the procedure's 'Announcement does not exist' - the two carry
        // the same SQLSTATE and the prose is never matched.
        assertThat(error.get("message").asText()).isEqualTo("Announcement not found.");
    }

    @Test
    @DisplayName("UC-21 B2: a body with no isActive is a field error rather than a deactivation")
    void aMissingActiveFlagIsAFieldError() throws Exception {
        // `{}` must not be read as "false". The DTO marks the field required, so an omission is a
        // field error - which is what keeps the endpoint's effect a function of what was sent.
        String token = adminToken();
        Long id = created(ADMIN_ANNOUNCEMENTS_URL, token, Map.of(
                "title", uniqueTitle(), "body", "A notice nobody asked to change.",
                "audience", "STUDENTS")).get("id").asLong();

        JsonNode error = refused(HttpMethod.PATCH, ANNOUNCEMENT_ITEM_URL.formatted(id), token,
                Map.of(), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");

        assertThat(fieldNamesIn(error)).containsExactly("isActive");
        assertThat(stringValueFrom("SELECT IF(is_active = 1, 'ACTIVE', 'WITHDRAWN') "
                + "FROM announcements WHERE id = ?", id))
                .as("the refusal left the row alone")
                .isEqualTo("ACTIVE");
    }

    // ==================================================================
    //  Helpers
    // ==================================================================

    private static String uniqueTitle() {
        return "Suite notice " + UUID.randomUUID();
    }

    private static List<String> titlesIn(JsonNode announcements) {
        List<String> titles = new ArrayList<>();
        if (announcements != null && announcements.isArray()) {
            announcements.forEach(announcement -> titles.add(announcement.get("title").asText()));
        }
        return titles;
    }

    private static JsonNode announcementWithId(JsonNode announcements, Long id) {
        for (JsonNode announcement : announcements) {
            if (announcement.get("id").asLong() == id) {
                return announcement;
            }
        }
        return null;
    }
}
