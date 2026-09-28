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

class AdminAnnouncementApiIT extends AbstractAdminApiIT {

    private static final String ANNOUNCEMENT_ITEM_URL = ADMIN_ANNOUNCEMENTS_URL + "/%d";
    private static final String DASHBOARD_URL = "/api/v1/dashboard";

    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final List<String> DOCUMENTED_FIELDS = List.of(
            "id", "title", "body", "severity", "audience", "startsAt", "endsAt", "isActive",
            "createdAt");

    @Test
    @DisplayName("UC-21: the list shows what a student's dashboard hides, and vice versa")
    void theAdminListAndTheStudentDashboardDisagreeOnPurpose() throws Exception {

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

        JsonNode listed = announcementWithId(
                ok(HttpMethod.GET, ADMIN_ANNOUNCEMENTS_URL, token, null), id);
        assertThat(listed).as("the administrator's list must include a withdrawn, expired notice")
                .isNotNull();
        assertThat(listed.get("audience").asText()).isEqualTo("ADMINS");
        assertThat(listed.get("isActive").asBoolean()).isFalse();

        JsonNode dashboard = ok(HttpMethod.GET, DASHBOARD_URL, studentToken(), null);
        assertThat(titlesIn(dashboard.get("announcements"))).doesNotContain(title);
    }

    @Test
    @DisplayName("UC-21: a live student notice reaches both lists, so the pair above is not vacuous")
    void aLiveStudentNoticeReachesBothLists() throws Exception {

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

            assertThat(fieldNamesOf(announcement)).isSubsetOf(DOCUMENTED_FIELDS);
            assertThat(fieldNamesOf(announcement))
                    .contains("id", "title", "body", "severity", "audience", "startsAt", "isActive",
                            "createdAt");
        }

        assertThat(allKeysIn(announcements)).doesNotContain("createdBy", "created_by", "author");
    }

    @Test
    @DisplayName("UC-21: an open-ended notice omits its end time instead of sending null")
    void anOpenEndedNoticeOmitsItsEndTime() throws Exception {

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

        assertThat(first.get(0)).isEqualTo(newestId);
    }

    @Test
    @DisplayName("UC-21 B1: the response is the row that was written, cross-checked against the audit row")
    void creatingReturnsTheRowThatWasCreated() throws Exception {

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

        LocalDateTime before = LocalDateTime.now(APPLICATION_ZONE).minusMinutes(1);
        String title = uniqueTitle();

        JsonNode response = created(ADMIN_ANNOUNCEMENTS_URL, adminToken(), Map.of(
                "title", title,
                "body", "Only the two required fields are given."));

        assertThat(response.get("severity").asText()).isEqualTo("INFO");
        assertThat(response.get("audience").asText()).isEqualTo("STUDENTS");
        assertThat(response.get("isActive").asBoolean()).isTrue();

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

        String token = adminToken();
        int auditsBefore = auditRowCount("ANNOUNCEMENT_CREATED");

        JsonNode endBeforeStart = refused(HttpMethod.POST, ADMIN_ANNOUNCEMENTS_URL, token, Map.of(
                "title", uniqueTitle(),
                "body", "The window runs backwards.",
                "startsAt", "2026-09-30T12:00:00",
                "endsAt", "2026-09-29T12:00:00"), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNamesIn(endBeforeStart)).containsExactly("endsAt");

        JsonNode endAtStart = refused(HttpMethod.POST, ADMIN_ANNOUNCEMENTS_URL, token, Map.of(
                "title", uniqueTitle(),
                "body", "The window is one instant long.",
                "startsAt", "2026-09-30T12:00:00",
                "endsAt", "2026-09-30T12:00:00"), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNamesIn(endAtStart)).containsExactly("endsAt");

        assertThat(auditRowCount("ANNOUNCEMENT_CREATED"))
                .as("a request the API refused before the call leaves no audit row")
                .isEqualTo(auditsBefore);
    }

    @Test
    @DisplayName("UC-21 B1: an end exactly after the start is accepted - the boundary from the other side")
    void anEndJustAfterTheStartIsAccepted() throws Exception {

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

        JsonNode error = refused(HttpMethod.POST, ADMIN_ANNOUNCEMENTS_URL, adminToken(), Map.of(
                "title", "T".repeat(151),
                "body", "A headline that is one character too long."),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");

        assertThat(fieldNamesIn(error)).containsExactly("title");
    }

    @Test
    @DisplayName("UC-21 B2: deactivating moves one column and leaves the notice's text alone")
    void deactivatingChangesOnlyTheActiveFlag() throws Exception {

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

        assertThat(countOf("SELECT COUNT(*) FROM admin_audit_log WHERE target_id = ? "
                + "AND action IN ('ANNOUNCEMENT_ACTIVATED', 'ANNOUNCEMENT_DEACTIVATED')", id))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("UC-21 B2: re-sending the current state is accepted and recorded")
    void resendingTheCurrentStateIsAccepted() throws Exception {

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

        assertThat(error.get("message").asText()).isEqualTo("Announcement not found.");
    }

    @Test
    @DisplayName("UC-21 B2: a body with no isActive is a field error rather than a deactivation")
    void aMissingActiveFlagIsAFieldError() throws Exception {

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
