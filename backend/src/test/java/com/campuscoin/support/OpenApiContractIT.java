package com.campuscoin.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Keeps the generated OpenAPI document, the written inventory and the controllers in step.
 *
 * <p>Section 13 phase 8 requires that the controller mappings, {@code docs/api/API_INVENTORY.md},
 * Swagger and the module documents all agree, and that no endpoint is undocumented, duplicated or
 * missing. Comparing them by hand is a check that decays the moment somebody adds a route; this
 * test reads the application's own {@code /api-docs} output, which is produced from the real
 * controller mappings, and asserts the properties the inventory promises.
 *
 * <p>What is asserted here is what can be asserted without parsing Markdown: that the set of
 * endpoints is exactly the documented one, that no path and method pair is registered twice, that
 * the endpoints a use case opens to anonymous callers carry no security requirement while the rest
 * require the bearer scheme, and that no response schema has grown a field the contract forbids.
 * The prose in {@code docs/api/*.md} and the table in the inventory are kept in step by review;
 * this test is the machine-checkable half.
 */
class OpenApiContractIT extends AbstractMySqlIntegrationTest {

    /**
     * Every endpoint the inventory documents, as {@code METHOD /path}. Adding a route without
     * adding it to {@code docs/api/API_INVENTORY.md} fails this test on purpose.
     */
    private static final Set<String> DOCUMENTED_ENDPOINTS = Set.of(
            "POST /api/v1/auth/register",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/logout",
            "POST /api/v1/auth/password-reset/request",
            "POST /api/v1/auth/password-reset/verify",
            "POST /api/v1/auth/password-reset/complete",
            "POST /api/v1/admin/auth/login",
            "GET /api/v1/profile/me",
            "PATCH /api/v1/profile/me",
            "PATCH /api/v1/profile/me/preferences",
            "GET /api/v1/categories",
            "GET /api/v1/categories/{id}",
            "POST /api/v1/categories",
            "PATCH /api/v1/categories/{id}",
            "DELETE /api/v1/categories/{id}",
            "GET /api/v1/transactions",
            "GET /api/v1/transactions/{id}",
            "POST /api/v1/transactions",
            "PATCH /api/v1/transactions/{id}",
            "DELETE /api/v1/transactions/{id}",
            "POST /api/v1/transactions/{id}/restore",
            "GET /api/v1/recurring-rules",
            "GET /api/v1/recurring-rules/{id}",
            "POST /api/v1/recurring-rules",
            "PATCH /api/v1/recurring-rules/{id}",
            "DELETE /api/v1/recurring-rules/{id}",
            "GET /api/v1/budgets",
            "GET /api/v1/budgets/{id}",
            "POST /api/v1/budgets",
            "PATCH /api/v1/budgets/{id}",
            "DELETE /api/v1/budgets/{id}",
            "GET /api/v1/notifications",
            "GET /api/v1/notifications/{id}",
            "POST /api/v1/notifications/{id}/read",
            "GET /api/v1/dashboard",
            "GET /api/v1/reports",
            "GET /api/v1/reports/spending",
            "GET /api/v1/tips",
            "GET /api/v1/tips/months",
            "POST /api/v1/tips/generate",
            "POST /api/v1/tips/{id}/state",
            "GET /api/v1/bookmarks",
            "POST /api/v1/bookmarks",
            "PATCH /api/v1/bookmarks/{id}",
            "DELETE /api/v1/bookmarks/{id}",
            "GET /api/v1/admin/users",
            "POST /api/v1/admin/users/{id}/status",
            "POST /api/v1/admin/users/{id}/password-reset",
            "GET /api/v1/admin/categories",
            "POST /api/v1/admin/categories",
            "PATCH /api/v1/admin/categories/{id}",
            "GET /api/v1/admin/announcements",
            "POST /api/v1/admin/announcements",
            "PATCH /api/v1/admin/announcements/{id}",
            "GET /api/v1/admin/tip-templates",
            "POST /api/v1/admin/tip-templates",
            "PATCH /api/v1/admin/tip-templates/{id}",
            "GET /api/v1/admin/settings",
            "PATCH /api/v1/admin/settings/{key}",
            "GET /api/v1/admin/stats",
            "GET /api/v1/admin/stats/top-categories",
            "GET /api/v1/recent-activity",
            "POST /api/v1/recent-activity",
            "GET /api/v1/anomalies",
            "POST /api/v1/anomalies/scan",
            "GET /api/v1/forecast",
            "POST /api/v1/ai/suggest-category",
            "GET /api/v1/insights",
            "GET /api/v1/insights/months",
            "POST /api/v1/insights/generate",
            "POST /api/v1/imports",
            "GET /api/v1/imports",
            "GET /api/v1/imports/{batchId}",
            "PATCH /api/v1/imports/{batchId}/rows/{rowId}",
            "POST /api/v1/imports/{batchId}/commit",
            "POST /api/v1/imports/{batchId}/cancel");

    /**
     * Endpoints that must stay reachable without a token. Each is either a sign-in step - nobody
     * can present a token before signing in - or the development-only documentation routes.
     */
    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "POST /api/v1/auth/register",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/password-reset/request",
            "POST /api/v1/auth/password-reset/verify",
            "POST /api/v1/auth/password-reset/complete",
            "POST /api/v1/admin/auth/login");

    /** Response field names that must never appear in the document, on any schema. */
    private static final Set<String> FORBIDDEN_SCHEMA_FIELDS = Set.of(
            "passwordHash", "password_hash", "tokenVersion", "token_version",
            "refreshToken", "refresh_token", "resetToken", "reset_token",
            "password", "sessionToken", "session_token");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Section 13 phase 8: the generated document lists exactly the documented endpoints")
    void documentMatchesTheInventory() throws Exception {
        JsonNode document = openApiDocument();

        Set<String> actual = new TreeSet<>();
        document.get("paths").fields().forEachRemaining(path ->
                path.getValue().fieldNames().forEachRemaining(method -> {
                    if (isOperation(method)) {
                        actual.add(method.toUpperCase() + " " + path.getKey());
                    }
                }));

        // Equality in both directions: a new endpoint fails as "undocumented", and a documented
        // endpoint that was renamed or removed fails as "missing". Set equality reports both.
        assertThat(actual).isEqualTo(new TreeSet<>(DOCUMENTED_ENDPOINTS));
    }

    @Test
    @DisplayName("Section 13 phase 8: no path and method pair is registered twice")
    void noEndpointIsDuplicated() throws Exception {
        JsonNode document = openApiDocument();

        // A path object keyed by method cannot hold the same method twice, so a duplicate in the
        // document is impossible by construction. What can happen is one capability reachable
        // through two URLs - the "convenience alias" section 13 forbids - so the guard is that no
        // two documented paths differ only by an alias suffix.
        Set<String> paths = new TreeSet<>();
        document.get("paths").fieldNames().forEachRemaining(paths::add);

        assertThat(paths).doesNotContain("/api/v1/users/me", "/api/v1/profile", "/api/v1/user/me");
        // A category is reached by id under its own collection rather than under the profile: a
        // student's categories are many, and /profile/me would imply a single owned record.
        assertThat(paths).doesNotContain("/api/v1/profile/me/categories",
                "/api/v1/categories/all", "/api/v1/categories/list");
        // A transaction is reached on its own collection under /transactions, and its soft delete is
        // an explicit /restore sub-resource rather than a PATCH of a flag: BR-09's history is
        // written by the procedures behind the sub-resource, and a body able to set `isDeleted`
        // would move the record without a log entry.
        assertThat(paths).doesNotContain("/api/v1/transactions/all", "/api/v1/transactions/list",
                "/api/v1/transactions/deleted", "/api/v1/profile/me/transactions");
        // A recurring rule is reached on its own collection for the same reasons, and its "stop it
        // posting" operation is a PATCH of `status` rather than a `/pause` or `/end` sub-resource:
        // pausing, resuming and ending are one column with three values, so three URLs would be
        // three names for one write. There is also no `/occurrences` sub-resource - the periods a
        // rule has covered are rows in `recurring_occurrences`, which the scheduler owns and which
        // the client reads indirectly through the transactions they produced.
        assertThat(paths).doesNotContain("/api/v1/recurring-rules/all", "/api/v1/recurring-rules/list",
                "/api/v1/recurring", "/api/v1/profile/me/recurring-rules",
                "/api/v1/recurring-rules/{id}/pause", "/api/v1/recurring-rules/{id}/resume",
                "/api/v1/recurring-rules/{id}/run", "/api/v1/recurring-rules/{id}/occurrences");
        // A budget is reached on its own collection, and its consumption is never a sub-resource:
        // the spent figure is a column of the same row, computed by `v_budget_consumption` on every
        // read, so a `/spend` or `/status` path would be a second name for the row itself. There is
        // also no alert-raising route. An alert belongs to the transaction that crossed a threshold
        // (BR-12), raised by the trigger, so a `/check` or `/recalculate` endpoint would be a second
        // trigger for the same procedure - one that could write the alert log row BR-12's unique key
        // has already, correctly, decided against.
        assertThat(paths).doesNotContain("/api/v1/budgets/all", "/api/v1/budgets/list",
                "/api/v1/profile/me/budgets", "/api/v1/budgets/{id}/spend",
                "/api/v1/budgets/{id}/status", "/api/v1/budgets/{id}/check",
                "/api/v1/budgets/recalculate", "/api/v1/budgets/alerts");
        // A notification is read on its own collection, and "mark read" is a POST to a `/read`
        // sub-path rather than a PATCH of `isRead`: the transition is one-way and the database owns
        // it, so an unread route would name a write the schema's `ck_notif_read` pair treats as not
        // something that happens. There is no create and no delete either - every row is written by
        // the procedure that owns it - so those URLs must not exist.
        assertThat(paths).doesNotContain("/api/v1/notifications/all", "/api/v1/notifications/list",
                "/api/v1/notifications/unread", "/api/v1/profile/me/notifications",
                "/api/v1/notifications/{id}/unread", "/api/v1/notifications/read-all");
        // A report is reached on its own collection, and there are exactly two URLs because there
        // are exactly two questions: one month's figures, and one window's bars. Every alias below
        // would be a second name for one of them. There is deliberately no `/export`: UC-16 exports
        // what UC-15 returns, and BR-18 requires the file to be generated at call time with nothing
        // buffered, which is what these two reads already do - so a CSV route would be a second way
        // to ask one question (section 13) rather than a new capability. There is no `/{id}` either,
        // because a report is not a stored row - it has no table and no identifier - and no
        // `/profile/me/reports`, because the collection is already the caller's own (BR-02).
        assertThat(paths).doesNotContain("/api/v1/reports/export", "/api/v1/reports/csv",
                "/api/v1/reports/download", "/api/v1/reports/summary", "/api/v1/reports/monthly",
                "/api/v1/reports/categories", "/api/v1/reports/all", "/api/v1/reports/list",
                "/api/v1/reports/{id}", "/api/v1/profile/me/reports");
        // A tip is reached on its own collection, and the two list views are one collection with a
        // sub-path rather than two aliases: `/tips` answers a month and `/tips/months` answers which
        // months exist, which is a different question and the answer a picker needs before it can
        // ask the first. There is no `/for-me` or `/my-tips` - the collection is already the
        // caller's own (BR-02) - and no `/pin`, `/dismiss` or `/unpin`: pinning, dismissing and
        // clearing are one column with three values, so three URLs would be three names for one
        // write, exactly as the notification module's single `/read` sub-path is one name for one
        // transition. There is no `/{id}` read either: a tip is only ever shown as part of its
        // month's ranked list, and a route that returned one on its own would be a second way to
        // read the same row without the ordering that makes it meaningful. There is no `/export` and
        // no `/history` for the same reason the reports have no export route.
        assertThat(paths).doesNotContain("/api/v1/tips/all", "/api/v1/tips/list",
                "/api/v1/tips/my-tips", "/api/v1/tips/for-me", "/api/v1/profile/me/tips",
                "/api/v1/tips/{id}", "/api/v1/tips/{id}/pin", "/api/v1/tips/{id}/unpin",
                "/api/v1/tips/{id}/dismiss", "/api/v1/tips/export", "/api/v1/tips/history",
                "/api/v1/tips/current");

        // A saved item is reached on its own collection, and its note is a PATCH of a field on the
        // {@code {id}} path rather than a /note sub-resource: a bookmark has exactly one field a
        // student may change, and one field is what PATCH already means - the same shape
        // /categories/{id} and /budgets/{id} use. There is no change of target: what a bookmark
        // points at is what it is, and the update trigger refuses to move it, so a route that
        // promised to would be promising something the schema declines. There is no /pin: pinning a
        // tip is a different act on a different table, under /tips/{id}/state (VĐ-03).
        assertThat(paths).doesNotContain("/api/v1/bookmarks/all", "/api/v1/bookmarks/list",
                "/api/v1/bookmarks/saved", "/api/v1/bookmarks/my-bookmarks",
                "/api/v1/profile/me/bookmarks", "/api/v1/bookmarks/{id}/note",
                "/api/v1/bookmarks/{id}/pin", "/api/v1/bookmarks/{id}/unpin",
                "/api/v1/bookmarks/{id}/insights");

        // Recent activity is reached on its own collection, and that collection is the whole surface:
        // GET lists the caller's entries and POST records one, which is the same list-and-create pair
        // every other collection here serves. There is no /{id} of any shape, and the reason is the
        // schema rather than taste - an entry has no identifier of its own to put in one. `uk_recent`
        // is (user_id, transaction_id, action), so the entry returned by the POST is addressed by the
        // transaction and the action it names, exactly as {@code RecordRecentActivityRequest}
        // addresses it; a surrogate key would be a fourth column the table does not have. There is no
        // /clear, /prune or /reset: the list is already bounded by the limit the caller passes and
        // already ordered by recency, so a second name for it would be the alias section 13 forbids
        // rather than a capability, and nothing in UC-26 lets a student erase their own trail. There
        // is no /profile/me/recent-activity, because the collection is already the caller's own
        // (BR-02), and none of /recent, /activities or /activity-log: one resource, one URL.
        assertThat(paths).doesNotContain("/api/v1/recent-activity/all", "/api/v1/recent-activity/list",
                "/api/v1/recent-activity/history", "/api/v1/recent-activity/clear",
                "/api/v1/recent-activity/reset", "/api/v1/profile/me/recent-activity",
                "/api/v1/recent", "/api/v1/recent-activities", "/api/v1/activities",
                "/api/v1/activity-log");

        // The administration surface is reached under /admin, and every one of its operations is
        // there - so an alias under the student paths would be a second name for a route that
        // already exists. There is no PUT anywhere: every writable resource in the module has a
        // PATCH, and each of the procedures behind them is an "upsert" or a "set one thing", so a
        // PUT would promise a whole-representation replace the database does not perform. There is
        // no DELETE either, and the reasons differ per resource: VĐ-06 gives an administrator no way
        // to delete an account at all (the reset link is the whole of that use case), and categories,
        // announcements and tip templates are withdrawn with `isActive: false` rather than removed,
        // because a template's history in `user_tips` restricts deletion and a retired default
        // category may still have students' records filed against it (BR-07). Activation is a
        // PATCH of the flag, not a /activate sub-resource, for the reason /tips/{id}/state is one
        // path and not three: active and inactive are one column with two values, and a second URL
        // would be a second name for one write. There is no GET /admin/users/{id} because the two
        // writes on that collection already return the row they changed, and no GET /admin/audit-log
        // because UC-22 B5 requires every administrative write to be logged, not to be browsable -
        // no view exists over the table. There is no /admin/insights, /admin/anomalies or /admin/ai
        // of any shape: those are module 12's, which is locked, so no route, no settings key and no
        // schema here may name them.
        assertThat(paths).doesNotContain("/api/v1/admin/users/{id}",
                "/api/v1/admin/users/{id}/disable", "/api/v1/admin/users/{id}/enable",
                "/api/v1/admin/users/{id}/reset-password", "/api/v1/admin/users/{id}/password",
                "/api/v1/admin/users/{id}/role", "/api/v1/admin/audit-log",
                "/api/v1/admin/audit", "/api/v1/admin/logs",
                "/api/v1/admin/categories/{id}/retire", "/api/v1/admin/categories/{id}/activate",
                "/api/v1/admin/categories/defaults",
                "/api/v1/admin/announcements/{id}/activate",
                "/api/v1/admin/announcements/{id}/deactivate",
                "/api/v1/admin/announcements/{id}/publish",
                "/api/v1/admin/tip-templates/{id}/activate",
                "/api/v1/admin/tip-templates/{id}/deactivate",
                "/api/v1/admin/settings/all", "/api/v1/admin/settings/list",
                "/api/v1/admin/thresholds",
                "/api/v1/admin/stats/categories", "/api/v1/admin/stats/usage",
                "/api/v1/admin/insights", "/api/v1/admin/anomalies", "/api/v1/admin/ai");

        // UC-24's flagged records are a read of the caller's own transactions, so they are reached on
        // their own collection and the scan that computes them on a sub-resource of it. There is no
        // /anomalies/{id}: a flagged record is a transaction, which already has GET
        // /transactions/{id}, and a second route to the same row would carry the mark but none of the
        // transaction's other fields - the alias section 13 forbids rather than a capability. There is
        // no /transactions/anomalies or /transactions/flagged either, for the reason the collection is
        // its own resource: the mark is computed across a student's whole history, so it is not a
        // filter over one page of the transaction list. There is no /clear or /reset, because clearing
        // a mark is not a write a student makes - correcting or trashing the record is (UC-10, BR-09),
        // and the next scan follows - and no /detect or /check, which would be a second name for the
        // one write-free scan the POST already is. The scan is a POST rather than a GET on the reason
        // every other state change here is: it is not safe-by-definition, since it writes the flags it
        // finds, and an endpoint a crawler or a prefetch could trigger would mark records by accident.
        assertThat(paths).doesNotContain("/api/v1/anomalies/{id}",
                "/api/v1/anomalies/all", "/api/v1/anomalies/list", "/api/v1/anomalies/flagged",
                "/api/v1/anomalies/clear", "/api/v1/anomalies/reset",
                "/api/v1/anomalies/detect", "/api/v1/anomalies/check", "/api/v1/anomalies/rescan",
                "/api/v1/transactions/anomalies", "/api/v1/transactions/flagged",
                "/api/v1/flagged-transactions", "/api/v1/anomaly-flags");

        // UC-25's projection is one read of the caller's own months, so it is one path with one method
        // and no sub-resources. There is no /forecast/{month} and no ?month= parameter: the month
        // projected is the one after the month in progress, and the month in progress is the server's
        // clock rather than the caller's choice - a parameterised month would let a caller probe months
        // the feature was not meant to project, and would put the window under client control when it is
        // a documented judgement rather than a client choice. There is no /forecast/history or
        // /forecast/months: the months the estimate rests on are published as `recentMonths` inside the
        // one response, and a second route returning the same rows would be the alias section 13 forbids.
        // There is no /predict or /projection: one resource, one URL. And there is no POST, because
        // nothing here writes - the projection is computed from rows the student already has, so it needs
        // no scan and leaves no mark, which is the difference between this path and /anomalies/scan.
        assertThat(paths).doesNotContain("/api/v1/forecast/{month}",
                "/api/v1/forecast/history", "/api/v1/forecast/months", "/api/v1/forecast/next",
                "/api/v1/forecast/projection", "/api/v1/forecast/summary",
                "/api/v1/predict", "/api/v1/projection", "/api/v1/predictions",
                "/api/v1/profile/me/forecast");

        // UC-08 proposes a category for one of the caller's own records, and the collection it lives in
        // is not the record's own. There is no /transactions/{id}/categorise, /transactions/{id}/suggest
        // or /transactions/categorise: the proposal is stored in three columns of `transactions`, but
        // module 4's entity deliberately leaves those columns unmapped so that no statement module 4
        // builds can write them - so the write goes through a path module 4 does not know about, and
        // reading it as a module-4 sub-resource would attribute it to the module that cannot perform it.
        // There is no /categories/{id}/suggest either: the route is not about a category, it is a
        // question asked about a record. Nor is there /ai/insights, /ai/anomalies or /ai/forecast: the
        // namespace holds the routes that ask the system to reason about the student's own data, and each
        // of those capabilities already has its own collection - a third name for one of them is the
        // alias section 13 forbids. And there is no GET: the call writes the proposal onto the record
        // and leaves a keyword mapping behind, so it is not a read, and a GET that wrote would be a route
        // a crawler or a prefetch could fire.
        assertThat(paths).doesNotContain("/api/v1/transactions/{id}/categorise",
                "/api/v1/transactions/{id}/categorize", "/api/v1/transactions/{id}/suggest",
                "/api/v1/transactions/suggest-category", "/api/v1/transactions/categorise",
                "/api/v1/categories/{id}/suggest", "/api/v1/ai", "/api/v1/ai/suggest",
                "/api/v1/ai/categorise", "/api/v1/ai/insights", "/api/v1/ai/anomalies",
                "/api/v1/ai/forecast", "/api/v1/ai/status");

        // UC-17's insight is reached on its own collection, and the collection is where the month is
        // read. There is no /insights/{month} and no /insights/2026-09: the month is a query parameter,
        // so one route answers every month and the picker list is a named sub-resource beside it rather
        // than a second shape of the same URL - the arrangement /tips and /tips/months already use, and
        // the reason a path-segment month would have to be a second route with the same handler. There
        // is no /insights/{id}: the row is addressed by its month, which is what a student knows, and
        // an insight has no identifier a client would ever hold. There is no /insights/latest,
        // /insights/current or /insights/summary: the current month is reached by asking for it with no
        // parameter at all, and three names for one read is the alias section 13 forbids. There is no
        // /insights/{month}/regenerate or /insights/refresh either: regenerating is one POST to
        // /generate that takes the same optional month, so a per-month sub-resource would be the same
        // write reachable two ways. And there is no PUT, PATCH or DELETE: an insight is not authored by
        // anybody - the figures are the student's own records and the prose is composed from them - so
        // there is no field a client may set and nothing a student may remove, which is why /insights
        // is a read while /generate is the only write.
        assertThat(paths).doesNotContain("/api/v1/insights/{month}",
                "/api/v1/insights/{id}", "/api/v1/insights/2026-09", "/api/v1/insights/latest",
                "/api/v1/insights/current", "/api/v1/insights/summary", "/api/v1/insights/refresh",
                "/api/v1/insights/regenerate", "/api/v1/insights/{month}/regenerate",
                "/api/v1/insights/generate/{month}", "/api/v1/profile/me/insights",
                "/api/v1/monthly-insights", "/api/v1/insight");

        // UC-11's routes are /imports and its four sub-resources, and the aliases section 13 forbids
        // are all the ways a second name for one of them could have been added. There is no
        // /imports/upload or /imports/csv: the collection's own POST is the upload, and a child path
        // would name a format the build does not have - a CSV is the only thing this importer reads.
        // There is no /imports/preview and no /imports/{batchId}/preview: uploading *is* previewing,
        // in one transaction, so a separate preview step would be a second route to a state the
        // first route already produces. There is no /imports/{batchId}/rows collection read: the
        // rows are nested in the batch, because the preview screen shows the counters and the rows
        // together and reading them apart could describe two different moments.
        // /imports/{batchId}/rows/{rowId}/category does not exist either - the row's own PATCH
        // writes its category and a deeper path would name the field inside the body it already
        // has. There is no /imports/{batchId}/status, /imports/{batchId}/cancel/undo or
        // /imports/{batchId}/reopen: a batch is committed or cancelled and neither is reversible,
        // and there is no procedure that would move one back to an open state.
        // /imports/{batchId}/errors, /imports/{batchId}/invalid and /imports/{batchId}/duplicates do
        // not exist: a row's verdict is a field of the row, returned with it, and three filtered
        // views of one list would be three answers to the same question. There is no PUT or DELETE
        // anywhere: the batch is created by the upload and its rows are written by the same request,
        // and UC-11 has no delete at all - a student abandons a batch by cancelling it, which keeps
        // the rows and the report.
        assertThat(paths).doesNotContain("/api/v1/imports/upload", "/api/v1/imports/csv",
                "/api/v1/imports/preview", "/api/v1/imports/{batchId}/preview",
                "/api/v1/imports/{batchId}/rows", "/api/v1/imports/{batchId}/rows/{rowId}/category",
                "/api/v1/imports/{batchId}/status", "/api/v1/imports/{batchId}/reopen",
                "/api/v1/imports/{batchId}/errors", "/api/v1/imports/{batchId}/invalid",
                "/api/v1/imports/{batchId}/duplicates", "/api/v1/imports/{batchId}/rollback",
                "/api/v1/imports/{id}", "/api/v1/import/{batchId}", "/api/v1/csv-imports",
                "/api/v1/import-batches");

        // Fifty-six distinct paths for seventy-six operations, and the difference is not an accident.
        // Sixteen paths carry more than one method: the ten collections /categories,
        // /transactions, /recurring-rules, /budgets, /bookmarks, /admin/categories,
        // /admin/announcements, /admin/tip-templates, /recent-activity and /imports each serve GET
        // and POST (list and create are one resource, so they are one URL); /profile/me serves GET
        // and PATCH;
        // the four {@code {id}} paths /categories/{id}, /transactions/{id}, /recurring-rules/{id} and
        // /budgets/{id} each serve GET, PATCH and DELETE; and /bookmarks/{id} serves PATCH and DELETE
        // only, because a saved item is read as part of the list and a route returning one on its own
        // would be a second way to read the same row with none of the ordering the list carries. That
        // is 34 operations on the sixteen multi-method paths. UC-24 adds
        // no multi-method path: /anomalies and /anomalies/scan are each one method, because the scan
        // is a sub-resource rather than a POST to the collection - it computes the marks across the
        // student's whole history rather than creating one record in the list it is named beside. So
        // 40 single-method paths and 76 operations in all. The
        // two report paths and the four single-method tip paths are among those forty: each
        // serves one method and no other, because a report is read and never written and a tip's
        // month, its months list and its generator are three distinct reads-or-actions rather than
        // one resource seen two ways. The fourth tip path, /tips/{id}/state, is a POST sub-resource
        // rather than a PATCH of a field, for the reason /notifications/{id}/read is: the state
        // change writes the state and the timestamp beside it together, and which pair is written
        // depends on the state. Module 11's two status changes follow the same rule and are POST
        // sub-resources for it - /admin/users/{id}/status writes the status, the session
        // revocations and `token_version` together, and /admin/users/{id}/password-reset writes a
        // token row and sends a message, which is not a field of the user at all. Its ten remaining
        // paths are single-method: /admin/settings/{key} and the three {@code {id}} PATCH paths are
        // written and never read on their own (their lists serve the reads), and /admin/stats,
        // /admin/stats/top-categories and the two collection GETs are read and never written.
        // Shared paths, not duplicated endpoints: a POST to /admin/categories and a GET of
        // /admin/categories are the same resource viewed two ways, whereas /tips/{id}/state and a
        // hypothetical /tips/{id} are two operations - and the second deliberately does not exist.
        // Module 12's /recent-activity is the ninth list-and-create pair for the same reason the
        // other eight are one URL, and it is the first to be added since module 11: reading the trail
        // and recording one act are two views of one collection the caller owns, so two URLs would be
        // two names for one resource. Its /anomalies pair is the one module 12 addition that is *not*
        // such a pair - the GET reads and the POST writes, but what the POST writes is not a new row
        // of the collection the GET lists: it re-examines the student's existing records and sets the
        // marks the GET then reports. One collection and one action over it is two paths, which is why
        // the count rose by two rather than by one. /forecast is the third module 12 addition and the
        // simplest: one read, one path, no sub-resource and no write - so the count rose by one for it,
        // and it is one of the single-method paths above rather than a list-and-create pair.
        // /ai/suggest-category is the fourth module 12 addition and a single-method path as well: one
        // POST asks one question about one record, and the collection holds nothing that could be
        // listed, so there is no GET for it to pair with and the count rose by one. UC-17 adds three
        // more, and none of them is a list-and-create pair: /insights is the read of one month, its
        // sibling /insights/months is the picker list, and /insights/generate is the one write - which
        // creates nothing a client can name, recomputing the month it is asked for in place. So the
        // three are single-method paths and the count rose by three rather than by one pair plus one.
        // UC-11 adds six operations and they are the first module 12 addition since /recent-activity
        // to contain a list-and-create pair: /imports serves POST (upload and preview) and GET (the
        // student's history), which is the tenth such pair and one path rather than two, because
        // uploading a file and listing the files uploaded are two views of one collection the student
        // owns. The other four are single-method. /imports/{batchId} is the preview read, and a
        // committed batch has no writable field left - the student changes a row or commits the
        // batch, never the batch itself - so there is no PATCH and no PUT beside it.
        // /imports/{batchId}/rows/{rowId} is a PATCH rather than a POST sub-resource because it
        // writes exactly one field of the row it names, and re-deciding the duplicate verdict is a
        // consequence of writing that field rather than a second thing the caller asked for (the
        // contrast is /tips/{id}/state, where the state change writes two columns together and which
        // pair is written depends on the state). /imports/{batchId}/commit and /imports/{batchId}/cancel
        // are POST sub-resources rather than a status update, because each is an action whose effect
        // exceeds any single column: a commit walks every importable row, generates transactions and
        // rewrites five counters. So the count rose by five paths and by one multi-method path.
        // That is 40 single-method paths and 16 multi-method ones - 56 paths for 76 operations.
        assertThat(paths).hasSize(56);
    }

    @Test
    @DisplayName("Section 7.5: the document marks public endpoints public and the rest bearer-guarded")
    void securityRequirementsMatchThePolicy() throws Exception {
        JsonNode document = openApiDocument();

        document.get("paths").fields().forEachRemaining(path ->
                path.getValue().fields().forEachRemaining(operation -> {
                    if (!isOperation(operation.getKey())) {
                        return;
                    }
                    String key = operation.getKey().toUpperCase() + " " + path.getKey();
                    JsonNode security = operation.getValue().get("security");

                    if (PUBLIC_ENDPOINTS.contains(key)) {
                        // An empty array or an absent block means "no authentication here"; a
                        // non-empty one would publish a padlock the endpoint does not have.
                        boolean declaredPublic = security == null || security.isEmpty();
                        assertThat(declaredPublic)
                                .as("%s must be documented as public", key)
                                .isTrue();
                    } else {
                        // Everything else must advertise the bearer scheme, and it must resolve to
                        // the scheme declared in components - a typo would silently drop the lock.
                        assertThat(security).as("%s must require a token", key).isNotNull();
                        assertThat(security.toString()).as("%s", key).contains("bearerAuth");
                    }
                }));

        // The scheme itself must be declared, or the per-operation references point at nothing.
        assertThat(document.at("/components/securitySchemes/bearerAuth/scheme").asText())
                .isEqualTo("bearer");
        assertThat(document.at("/components/securitySchemes/bearerAuth/type").asText())
                .isEqualTo("http");
    }

    @Test
    @DisplayName("Section 7.2: no response schema exposes a password, hash or internal token")
    void noResponseSchemaExposesSensitiveFields() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");
        assertThat(schemas.isObject()).isTrue();

        // Only schemas that are actually RETURNED are checked. A request DTO legitimately carries
        // a password - that is the sign-in and register contract - so banning the field name
        // outright would be wrong. The guarantee section 7.2 makes is about what leaves the
        // server, so the rule is applied to the schemas reached from a success response.
        Set<String> responseSchemas = new TreeSet<>();
        document.get("paths").fields().forEachRemaining(path ->
                path.getValue().fields().forEachRemaining(operation -> {
                    if (!isOperation(operation.getKey())) {
                        return;
                    }
                    JsonNode responses = operation.getValue().get("responses");
                    if (responses == null) {
                        return;
                    }
                    responses.fields().forEachRemaining(response ->
                            collectSchemaReferences(response.getValue(), responseSchemas));
                }));

        // The set must be non-trivial, or the assertion below would pass by checking nothing.
        assertThat(responseSchemas).contains("ProfileResponse", "AuthResponse", "ApiError",
                "CategoryResponse", "TransactionResponse", "RecurringRuleResponse",
                "BudgetResponse", "NotificationResponse", "DashboardResponse", "ReportResponse",
                "SpendingSeriesResponse", "TipListResponse", "TipResponse", "TipMonthsResponse",
                "BookmarkResponse", "AdminUserResponse", "AnnouncementResponse",
                "TipTemplateResponse", "SystemSettingResponse", "AdminUsageStatsResponse",
                "AdminTopCategoryResponse", "AdminPasswordResetResponse",
                "RecentActivityResponse", "RecentActivityListResponse",
                "FlaggedTransactionListResponse", "AnomalyScanResponse", "ForecastResponse",
                "CategorySuggestionResponse", "MonthlyInsightResponse", "InsightMonthsResponse",
                "ImportBatchResponse", "ImportBatchListResponse", "ImportRowResponse");
        // ImportSummaryResponse is deliberately absent: it is never a response of its own, only the
        // element type of ImportBatchListResponse.entries, and the collector above reads the response
        // object rather than descending into a referenced schema's own properties - so listing it here
        // would assert that something is true of a reference that does not exist. It is pinned by its
        // own field-set assertion in ImportApiIT instead, which is where a nested-only shape belongs.

        for (String name : responseSchemas) {
            JsonNode properties = schemas.at("/" + name + "/properties");
            if (!properties.isObject()) {
                continue;
            }
            properties.fieldNames().forEachRemaining(field ->
                    assertThat(FORBIDDEN_SCHEMA_FIELDS)
                            .as("response schema %s must not expose %s", name, field)
                            .doesNotContain(field));
        }
    }

    @Test
    @DisplayName("Section 13 phase 7: the profile contract has the documented shape")
    void profileSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // The response is nine fields and the request is four. A field added to either without a
        // UC to justify it - or one of the two security columns leaking in - changes these, so the
        // numbers are pinned rather than checked loosely.
        assertThat(fieldNames(schemas, "ProfileResponse")).containsExactlyInAnyOrder(
                "id", "fullName", "email", "academicYear", "monthlyAllowanceBaseline",
                "monthlySavingsGoal", "currency", "themePreference", "fontScale");
        assertThat(fieldNames(schemas, "UpdateProfileRequest")).containsExactlyInAnyOrder(
                "fullName", "academicYear", "monthlyAllowanceBaseline", "monthlySavingsGoal");
        assertThat(fieldNames(schemas, "UpdatePreferencesRequest"))
                .containsExactlyInAnyOrder("themePreference", "fontScale");

        // The enums must be published as their member names, which is what makes the contract
        // discoverable from Swagger without reading the database. They are inlined into the
        // property rather than emitted as named components, so they are read from there.
        assertThat(enumValues(schemas, "ProfileResponse", "themePreference"))
                .containsExactlyInAnyOrder("LIGHT", "DARK", "SYSTEM");
        assertThat(enumValues(schemas, "ProfileResponse", "fontScale"))
                .containsExactlyInAnyOrder("SMALL", "MEDIUM", "LARGE", "XLARGE");
        // The request schema must advertise the same vocabulary as the response, or a client
        // reading one and sending to the other would be misled.
        assertThat(enumValues(schemas, "UpdatePreferencesRequest", "themePreference"))
                .containsExactlyInAnyOrder("LIGHT", "DARK", "SYSTEM");
        assertThat(enumValues(schemas, "UpdatePreferencesRequest", "fontScale"))
                .containsExactlyInAnyOrder("SMALL", "MEDIUM", "LARGE", "XLARGE");

        // Section 19: the field-error property is `field` project-wide, never `path`.
        assertThat(fieldNames(schemas, "FieldError")).containsExactlyInAnyOrder("field", "message");
        assertThat(fieldNames(schemas, "ApiError")).containsExactlyInAnyOrder(
                "timestamp", "status", "errorCode", "message", "path", "fieldErrors");
    }

    @Test
    @DisplayName("Section 13 phase 7: the category contract has the documented shape")
    void categorySchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // The response is nine fields. `userId` and `createdBy` are deliberately not among them:
        // the API never says who owns a row, and an owner field would invite a client to send one.
        assertThat(fieldNames(schemas, "CategoryResponse")).containsExactlyInAnyOrder(
                "id", "name", "type", "icon", "color", "isDefault", "isActive", "sortOrder",
                "description");
        assertThat(fieldNames(schemas, "CategoryResponse")).doesNotContain("userId", "user_id",
                "createdBy", "created_by");

        // The create request requires name and type and accepts the rest.
        assertThat(fieldNames(schemas, "CreateCategoryRequest")).containsExactlyInAnyOrder(
                "name", "type", "icon", "color", "description", "sortOrder", "isActive");
        // The update request accepts the same set with no field required, which is what makes it a
        // partial update. `isDefault` is absent from both, so scope cannot be requested.
        assertThat(fieldNames(schemas, "UpdateCategoryRequest")).containsExactlyInAnyOrder(
                "name", "type", "icon", "color", "description", "sortOrder", "isActive");
        assertThat(fieldNames(schemas, "UpdateCategoryRequest")).doesNotContain("isDefault",
                "userId", "createdBy");

        // `type` publishes the database's vocabulary on both sides, so a client reading the
        // response and writing the request sees the same two values.
        assertThat(enumValues(schemas, "CategoryResponse", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");
        assertThat(enumValues(schemas, "CreateCategoryRequest", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");
        assertThat(enumValues(schemas, "UpdateCategoryRequest", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");
    }

    @Test
    @DisplayName("Section 13 phase 7: the transaction contract has the documented shape")
    void transactionSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // Twelve fields. The category is flattened, `type` is derived from it (BR-05), and the
        // row's own creation and modification timestamps are deliberately absent - as they are on
        // CategoryResponse - because no use case shows them.
        assertThat(fieldNames(schemas, "TransactionResponse")).containsExactlyInAnyOrder(
                "id", "categoryId", "categoryName", "categoryIcon", "categoryColor", "type",
                "amount", "txnDate", "description", "source", "isDeleted", "deletedAt");
        assertThat(fieldNames(schemas, "TransactionResponse"))
                .doesNotContain("userId", "user_id", "createdAt", "updatedAt");

        // The three fields a client must never be able to send. `type` is derived from the category
        // (BR-05) - `transactions` has no type column - so a request that carries one is ignored
        // rather than honoured; `source` decides whether the BR-08 future-date check applies, so a
        // client able to claim RECURRING could date a record ahead; `userId` would be a direct
        // ownership bypass.
        for (String request : new String[] {"CreateTransactionRequest", "UpdateTransactionRequest"}) {
            assertThat(fieldNames(schemas, request))
                    .as("%s must not let a client choose its own type, source or owner", request)
                    .doesNotContain("type", "source", "userId", "user_id", "isDeleted",
                            "deletedAt", "aiSuggestedCategoryId", "aiConfidence", "aiOverridden",
                            "isFlagged", "flagType", "flagNote", "recurringRuleId",
                            "importBatchId");
        }

        // Create requires the three columns the schema makes NOT NULL and accepts the optional note;
        // update accepts the same four with none required, which is what makes it a partial update.
        assertThat(fieldNames(schemas, "CreateTransactionRequest"))
                .containsExactlyInAnyOrder("categoryId", "amount", "txnDate", "description");
        assertThat(fieldNames(schemas, "UpdateTransactionRequest"))
                .containsExactlyInAnyOrder("categoryId", "amount", "txnDate", "description");

        // `type` is published with the category vocabulary rather than with a private one, so a
        // client reading it from a category and from a transaction sees one set of words (BR-05).
        assertThat(enumValues(schemas, "TransactionResponse", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");
        // `source` publishes the column's ENUM, which is how a client can tell a manual record from
        // one the scheduler or the import created in a later module.
        assertThat(enumValues(schemas, "TransactionResponse", "source"))
                .containsExactlyInAnyOrder("MANUAL", "CSV", "RECURRING");
    }

    @Test
    @DisplayName("Section 13 phase 7: the recurring-rule contract has the documented shape")
    void recurringSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // Fifteen fields, all of which the integration test pins as a subset of what a rule response
        // may contain. `userId` is absent for the usual reason. `startDate` is published but absent
        // from both request schemas' writable set on update - it is the rule's origin, and the
        // periods already posted are a function of it.
        assertThat(fieldNames(schemas, "RecurringRuleResponse")).containsExactlyInAnyOrder(
                "id", "categoryId", "categoryName", "categoryIcon", "categoryColor", "type",
                "amount", "description", "frequency", "intervalCount", "startDate", "endDate",
                "nextRunDate", "lastRunDate", "status");
        assertThat(fieldNames(schemas, "RecurringRuleResponse"))
                .doesNotContain("userId", "user_id", "createdAt", "updatedAt",
                        "dayOfMonth", "dayOfWeek", "lastPostedAt");

        // The fields a client must never send. `type` is the category's value (BR-05) and is
        // re-derived on every write, so accepting one would let a rule claim a direction its
        // category disagrees with. A client able to move `lastRunDate` or the occurrence bookkeeping
        // could make the scheduler skip or repeat periods. `startDate` is absent from the update
        // request for the reason the response docstring gives.
        for (String request : new String[] {"CreateRecurringRuleRequest", "UpdateRecurringRuleRequest"}) {
            assertThat(fieldNames(schemas, request))
                    .as("%s must not let a client choose its own type or history", request)
                    .doesNotContain("type", "userId", "user_id", "lastRunDate", "last_run_date",
                            "dayOfMonth", "dayOfWeek", "createdAt", "updatedAt");
        }
        assertThat(fieldNames(schemas, "UpdateRecurringRuleRequest")).doesNotContain("startDate",
                "start_date");

        // Both requests name the same writable set, which is what keeps the create and update
        // contracts from drifting; create additionally requires the four the schema makes NOT NULL,
        // which Bean Validation expresses rather than the document.
        assertThat(fieldNames(schemas, "UpdateRecurringRuleRequest")).containsExactlyInAnyOrder(
                "categoryId", "amount", "description", "frequency", "intervalCount", "endDate",
                "nextRunDate", "status");
        assertThat(fieldNames(schemas, "CreateRecurringRuleRequest")).containsExactlyInAnyOrder(
                "categoryId", "amount", "description", "frequency", "intervalCount", "startDate",
                "endDate", "nextRunDate");

        // A client reading a response and writing an update must see one vocabulary on each side.
        // These three are the columns the scheduler actually branches on, so a value missing on
        // either side would be a schedule the client cannot express or cannot read back.
        assertThat(enumValues(schemas, "RecurringRuleResponse", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");
        assertThat(enumValues(schemas, "RecurringRuleResponse", "frequency"))
                .containsExactlyInAnyOrder("DAILY", "WEEKLY", "MONTHLY", "QUARTERLY", "YEARLY");
        assertThat(enumValues(schemas, "RecurringRuleResponse", "status"))
                .containsExactlyInAnyOrder("ACTIVE", "PAUSED", "ENDED");
        assertThat(enumValues(schemas, "CreateRecurringRuleRequest", "frequency"))
                .isEqualTo(enumValues(schemas, "RecurringRuleResponse", "frequency"));
        assertThat(enumValues(schemas, "UpdateRecurringRuleRequest", "frequency"))
                .isEqualTo(enumValues(schemas, "RecurringRuleResponse", "frequency"));
        assertThat(enumValues(schemas, "UpdateRecurringRuleRequest", "status"))
                .isEqualTo(enumValues(schemas, "RecurringRuleResponse", "status"));

        // `endDate` is a String on both request schemas, not a date. It is the one date in a rule
        // that can be removed - sending "" clears it - and a record of nullable fields cannot tell
        // "absent" from "explicit null", so a typed date would leave no way to say "no end date".
        // Publishing it as a date would promise a client the wrong wire type.
        assertThat(schemas.at("/CreateRecurringRuleRequest/properties/endDate/type").asText())
                .isEqualTo("string");
        assertThat(schemas.at("/UpdateRecurringRuleRequest/properties/endDate/type").asText())
                .isEqualTo("string");
    }

    @Test
    @DisplayName("Section 13 phase 7: the budget contract has the documented shape")
    void budgetSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // Eleven fields, all of which the integration test pins as a subset of what a budget response
        // may contain. The consumption figures are published because the screen is a progress bar;
        // they are all the database's, read from v_budget_consumption on every call.
        assertThat(fieldNames(schemas, "BudgetResponse")).containsExactlyInAnyOrder(
                "id", "categoryId", "categoryName", "categoryIcon", "categoryColor", "periodMonth",
                "limitAmount", "spentAmount", "remainingAmount", "consumedPct",
                "consumptionStatus");
        assertThat(fieldNames(schemas, "BudgetResponse"))
                .doesNotContain("userId", "user_id", "createdAt", "updatedAt",
                        "type", "categoryType", "isActive");

        // The fields a client must never send. A budget's owner is the token (BR-02); the spend and
        // the status are derived by the view, so a client able to send one could show itself on
        // track while the alert log disagreed; `type` is the category's value (BR-11) and is
        // re-derived on every write. `isDeleted` has no meaning on this table at all - a budget is
        // removed, not trashed.
        for (String request : new String[] {"CreateBudgetRequest", "UpdateBudgetRequest"}) {
            assertThat(fieldNames(schemas, request))
                    .as("%s must not let a client choose its own owner, spend or status", request)
                    .doesNotContain("id", "userId", "user_id", "spentAmount", "spent_amount",
                            "remainingAmount", "consumedPct", "consumptionStatus", "type",
                            "isDeleted", "createdAt", "updatedAt");
        }

        // The update contract is exactly one field, which is the whole of what UC-13 allows to
        // change. Both the category and the month are the row's identity - uk_budget_user_cat_month
        // is built from them (BR-11) - and a writable category would additionally be a second way to
        // put a limit on an income category, slipping past the check the insert trigger makes.
        assertThat(fieldNames(schemas, "UpdateBudgetRequest")).containsExactlyInAnyOrder(
                "limitAmount");
        assertThat(fieldNames(schemas, "CreateBudgetRequest")).containsExactlyInAnyOrder(
                "categoryId", "periodMonth", "limitAmount");

        // A client reading a consumption status and choosing a threshold vocabulary must see one set
        // of values. These three are the CASE branches v_budget_consumption actually returns, so a
        // member missing here would be a state the client cannot render.
        assertThat(enumValues(schemas, "BudgetResponse", "consumptionStatus"))
                .containsExactlyInAnyOrder("ON_TRACK", "NEAR", "EXCEEDED");

        // `periodMonth` is a String on both sides, not a date. A student names a month as `yyyy-MM`,
        // and the column's DATE - the first of that month - is how it is stored rather than
        // something to send. A typed date would invite a caller to send the 15th and receive a
        // constraint violation instead of an answer.
        assertThat(schemas.at("/BudgetResponse/properties/periodMonth/type").asText())
                .isEqualTo("string");
        assertThat(schemas.at("/CreateBudgetRequest/properties/periodMonth/type").asText())
                .isEqualTo("string");
    }

    @Test
    @DisplayName("Section 13 phase 7: the notification contract has the documented shape")
    void notificationSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // Ten fields. `userId` is absent for the usual reason and it matters most here: a
        // notification's title and body are readable prose about one student's spending, so a
        // client that could send an owner would be one that could read somebody else's message.
        assertThat(fieldNames(schemas, "NotificationResponse")).containsExactlyInAnyOrder(
                "id", "type", "title", "body", "linkUrl", "refEntityType", "refEntityId", "isRead",
                "readAt", "createdAt");
        assertThat(fieldNames(schemas, "NotificationResponse"))
                .doesNotContain("userId", "user_id", "updatedAt", "isDeleted", "deletedAt");

        // There is no request body for any notification endpoint - reading and marking read take no
        // payload - so no schema may exist that would let a client author, edit or delete a message.
        // Every row is written by the procedure that owns it.
        for (String forbidden : new String[] {"CreateNotificationRequest",
                "UpdateNotificationRequest", "NotificationRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: nothing about a notification is the student's to author",
                            forbidden)
                    .isFalse();
        }

        // The type vocabulary the column declares, published in full. A budget alert is the only
        // pair this module raises, but the others belong to the procedures of later modules and are
        // returned by this same list - so a client switching on `type` needs them all, or it would
        // have to guess at values it will genuinely receive.
        assertThat(enumValues(schemas, "NotificationResponse", "type"))
                .containsExactlyInAnyOrder("BUDGET_NEAR", "BUDGET_EXCEEDED", "ANNOUNCEMENT",
                        "SYSTEM", "INSIGHT_READY", "TIP", "RECURRING_POSTED");

        // `readAt` is published, and it is published as nullable rather than required: an unread
        // message has no timestamp at all, which is what ck_notif_read requires of the pair.
        assertThat(schemas.at("/NotificationResponse/properties/readAt").isMissingNode()).isFalse();
        assertThat(schemas.at("/NotificationResponse/properties/isRead/type").asText())
                .isEqualTo("boolean");
    }

    @Test
    @DisplayName("Section 13 phase 7: the report contract has the documented shape")
    void reportSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // Six fields. `userId` is absent for the usual reason and it matters most here: a report is
        // one named student's month, so a client able to send an owner would be one able to read
        // somebody else's figures (BR-02). There is no echoed request parameter and no `from`/`to`
        // pair - the window belongs to the spending series, whose own response publishes it.
        assertThat(fieldNames(schemas, "ReportResponse")).containsExactlyInAnyOrder(
                "periodMonth", "currency", "totals", "expenseByCategory", "incomeByCategory",
                "sixMonthTrend");
        assertThat(fieldNames(schemas, "ReportResponse"))
                .doesNotContain("userId", "user_id", "month", "from", "to");

        // The totals block is four figures, none of them required: a month with no records publishes
        // a totals object whose figures are absent rather than zero, because "recorded nothing" and
        // "netted to nothing" are different statements.
        assertThat(fieldNames(schemas, "ReportTotalsResponse")).containsExactlyInAnyOrder(
                "income", "expense", "net", "transactionCount");

        // A slice carries the presentation columns the categories screen uses, its own total and
        // count, its type so the two blocks share one shape, and the share - the one figure this
        // module computes. It is not pinned as summing to 100; see ReportCategoryResponse.
        assertThat(fieldNames(schemas, "ReportCategoryResponse")).containsExactlyInAnyOrder(
                "categoryId", "categoryName", "categoryIcon", "categoryColor", "type", "total",
                "percentage", "transactionCount");
        assertThat(fieldNames(schemas, "ReportCategoryResponse"))
                .doesNotContain("userId", "user_id", "categoryType");
        assertThat(enumValues(schemas, "ReportCategoryResponse", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");

        // A trend point names its own month, so the six points cannot be mislabelled by the month
        // the rest of the report describes (BR-17).
        assertThat(fieldNames(schemas, "ReportTrendPointResponse")).containsExactlyInAnyOrder(
                "periodMonth", "income", "expense", "net");

        // The series publishes the window it covered and the granularity it used, so a chart knows
        // where its axis starts and ends without a bar for every empty interval.
        assertThat(fieldNames(schemas, "SpendingSeriesResponse")).containsExactlyInAnyOrder(
                "granularity", "from", "to", "currency", "totalExpense", "points");
        assertThat(fieldNames(schemas, "SpendingPointResponse")).containsExactlyInAnyOrder(
                "intervalStart", "intervalEnd", "totalExpense", "transactionCount");

        // A point's boundaries are the interval's own, and both are published: a weekly point is a
        // whole ISO week, so its start and end need not lie in the month being reported.
        assertThat(fieldNames(schemas, "SpendingPointResponse"))
                .doesNotContain("userId", "user_id", "weekStart", "weekEnd");

        // There is no request schema for any report endpoint, because neither takes a body: a report
        // is read, never written. A `CreateReportRequest` or an `ExportReportRequest` would name a
        // capability the module deliberately does not have - UC-16 exports what UC-15 returns.
        for (String forbidden : new String[] {"CreateReportRequest", "UpdateReportRequest",
                "ReportRequest", "ExportReportRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: a report is read and never written", forbidden)
                    .isFalse();
        }

        // `periodMonth` is a String on the response, not a date. A student names a month as
        // `yyyy-MM`, and a typed date would invite a caller to read a first-of-month DATE as if the
        // day carried meaning - the convention every other month in this API follows.
        assertThat(schemas.at("/ReportResponse/properties/periodMonth/type").asText())
                .isEqualTo("string");
        assertThat(schemas.at("/ReportTrendPointResponse/properties/periodMonth/type").asText())
                .isEqualTo("string");

        // `granularity` publishes the two breakdowns the schema actually computes, so a client can
        // discover the supported values from Swagger rather than from a rejected request.
        assertThat(enumValues(schemas, "SpendingSeriesResponse", "granularity"))
                .containsExactlyInAnyOrder("DAILY", "WEEKLY");
    }

    @Test
    @DisplayName("Section 13 phase 7: the saving-tip contract has the documented shape")
    void tipSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // Six fields. `userId` is absent for the usual reason and it matters most here: a tip's
        // title and body are readable prose naming a category and an amount from one student's own
        // spending, so a client able to send an owner would be one able to read somebody else's
        // advice. `rankScore` is absent because it is the ordering, not a field to render - the array
        // already arrives ranked (BR-14) - and publishing the raw score would invite a client to
        // re-sort and disagree with the database. `periodMonth` is on the wrapper, not the row.
        assertThat(fieldNames(schemas, "TipResponse")).containsExactlyInAnyOrder(
                "id", "categoryId", "title", "body", "potentialSaving", "state");
        assertThat(fieldNames(schemas, "TipResponse"))
                .doesNotContain("userId", "user_id", "rankScore", "rank_score", "periodMonth",
                        "tipTemplateId", "tip_template_id", "dedupeKey", "dedupe_key",
                        "generatedAt", "pinnedAt", "dismissedAt", "createdAt", "updatedAt");

        // The month is named once, on the wrapper, and `tips` is always present - an empty array is
        // the honest answer for a month with nothing to show, so the property is never omitted.
        assertThat(fieldNames(schemas, "TipListResponse"))
                .containsExactlyInAnyOrder("periodMonth", "tips");
        assertThat(fieldNames(schemas, "TipMonthsResponse")).containsExactlyInAnyOrder("months");

        // The state change is a POST to a sub-path with one field, which is the whole of what UC-18
        // lets a student change. A body able to set `pinnedAt` or `dismissedAt` would move a
        // timestamp without the state it is paired with, which is the row ck_tip_state exists to
        // refuse; `userId` would be a direct ownership bypass.
        assertThat(fieldNames(schemas, "UpdateTipStateRequest")).containsExactlyInAnyOrder("state");
        assertThat(fieldNames(schemas, "UpdateTipStateRequest"))
                .doesNotContain("userId", "id", "pinnedAt", "dismissedAt", "periodMonth",
                        "title", "body", "potentialSaving");

        // There is no create, edit or delete request schema: every tip is written by
        // sp_generate_tips, and the only writable column a student owns is the state. A
        // CreateTipRequest or DeleteTipRequest would name a capability the module deliberately does
        // not have - and a delete would remove the row whose dedupe key is what keeps a dismissed
        // tip from coming back.
        for (String forbidden : new String[] {"CreateTipRequest", "UpdateTipRequest",
                "DeleteTipRequest", "TipRequest", "GenerateTipsRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: a tip is generated by the database, never authored",
                            forbidden)
                    .isFalse();
        }

        // Both sides publish the same three members, because both are typed by the one `TipState`.
        // That is deliberate rather than incidental: the request has to be able to name DISMISSED or
        // the transition this module exists for would be unexpressible, and the response field is the
        // same type because a tip's state is one column - two Java enums over one column would be two
        // vocabularies that could drift. The response therefore advertises a member it never returns,
        // which the schema cannot prevent and which the integration test pins instead:
        // `TipsApiIT.aDismissedTipStaysDismissedAcrossAGeneration` asserts a dismissed tip is absent
        // from the list. The enum is the vocabulary, and the rule that only two of its members reach
        // a client is the view's, not the type's.
        assertThat(enumValues(schemas, "UpdateTipStateRequest", "state"))
                .containsExactlyInAnyOrder("NEW", "PINNED", "DISMISSED");
        assertThat(enumValues(schemas, "TipResponse", "state"))
                .isEqualTo(enumValues(schemas, "UpdateTipStateRequest", "state"));

        // `periodMonth` is a String on both tips responses, not a date: a student names a month as
        // `yyyy-MM`, and a typed date would invite a caller to read a first-of-month DATE as if the
        // day carried meaning - the convention every other month in this API follows.
        assertThat(schemas.at("/TipListResponse/properties/periodMonth/type").asText())
                .isEqualTo("string");
        assertThat(schemas.at("/TipMonthsResponse/properties/months/items/type").asText())
                .isEqualTo("string");

        // `categoryId` is nullable. The savings-goal tip and the "not enough data yet" tip are about
        // the month as a whole, so a client must be able to tell "no category" from "category 0".
        assertThat(schemas.at("/TipResponse/properties/categoryId").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("Section 13 phase 7: the bookmark contract has the documented shape")
    void bookmarkSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // Ten fields, and the tip's own words travel with the saved entry: UC-19's postcondition is
        // that a marked item can be looked at again later, so the client renders the card from this
        // one response rather than resolving a tip id per row. `userId` is absent for the usual
        // reason - the query already applied ownership - and it matters more than usual here because
        // an entry is advice about one student's spending plus a note they wrote.
        assertThat(fieldNames(schemas, "BookmarkResponse")).containsExactlyInAnyOrder(
                "id", "itemType", "tipId", "tipTitle", "tipBody", "tipPotentialSaving",
                "tipState", "tipMonth", "note", "createdAt");
        assertThat(fieldNames(schemas, "BookmarkResponse"))
                .doesNotContain("userId", "user_id", "insightId", "insight_id", "createdBy",
                        "dedupeKey", "dedupe_key", "rankScore", "rank_score", "categoryId",
                        "tipCategoryId", "updatedAt", "deletedAt");

        // The request names the item by kind and id, which is how the schema's own CHECK reads the
        // pair: `item_type` selects which of `tip_id` / `insight_id` must be set. A field called
        // `tipId` would say the endpoint is about tips while accepting `INSIGHT`.
        assertThat(fieldNames(schemas, "CreateBookmarkRequest"))
                .containsExactlyInAnyOrder("itemType", "itemId", "note");
        assertThat(fieldNames(schemas, "CreateBookmarkRequest"))
                .doesNotContain("userId", "user_id", "tipId", "insightId", "createdAt",
                        "dedupeKey", "dedupe_key");

        // The note edit is one field, because the note is the only part of a bookmark UC-19 lets a
        // student change. A body able to set `tipId` would be offering to move a bookmark onto a
        // different item, which `trg_bookmarks_before_update` exists to refuse; `createdAt` is the
        // database's and is what the list orders by.
        assertThat(fieldNames(schemas, "UpdateBookmarkNoteRequest")).containsExactlyInAnyOrder("note");
        assertThat(fieldNames(schemas, "UpdateBookmarkNoteRequest"))
                .doesNotContain("itemType", "itemId", "tipId", "insightId", "userId", "id",
                        "createdAt");

        // Both `itemType` and `tipState` are published as their member names, so the vocabulary is
        // discoverable from Swagger without reading the database.
        //
        // `itemType` advertises `INSIGHT` because the column holds it and this build refuses it where
        // the reason can be explained - a narrowed enum would turn that request into a JSON parsing
        // failure whose message says the value is invalid, which is both untrue and unhelpful. The
        // request and the response must publish the same two members, since both are typed by the one
        // `BookmarkItemType`; that only `TIP` is ever written or returned is the service's rule, and
        // `BookmarksApiIT` pins it.
        assertThat(enumValues(schemas, "CreateBookmarkRequest", "itemType"))
                .containsExactlyInAnyOrder("TIP", "INSIGHT");
        assertThat(enumValues(schemas, "BookmarkResponse", "itemType"))
                .isEqualTo(enumValues(schemas, "CreateBookmarkRequest", "itemType"));
        assertThat(enumValues(schemas, "BookmarkResponse", "tipState"))
                .containsExactlyInAnyOrder("NEW", "PINNED", "DISMISSED");

        // There is no schema that could re-point a bookmark and none that could create a tip. Both
        // would be capabilities the module deliberately does not have.
        for (String forbidden : new String[] {"UpdateBookmarkRequest", "CreateBookmarkNoteRequest",
                "BookmarkNoteRequest", "MoveBookmarkRequest", "CreateInsightRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: a bookmark's target is what it is", forbidden)
                    .isFalse();
        }

        // `note` is nullable - a bookmark without one is the ordinary case - and `tipId` is nullable
        // because the projection reads the joined tip as nullable. A client must be able to tell "no
        // note" from "an empty note", which is why the field is omitted rather than sent blank.
        assertThat(schemas.at("/BookmarkResponse/properties/note").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("Section 13 phase 7: the administration contract has the documented shape")
    void adminSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // Eight fields. The exclusion list is the load-bearing part of this schema rather than the
        // inclusion list: `password_hash` and `token_version` are the two columns an account row
        // carries that must never leave the server, and `token_version` is the subtler of them - it is
        // the entire security meaning of a JWT's `tv` claim, so publishing it would let an attacker
        // decide whether a stolen token is still live. `monthly_allowance_baseline`,
        // `monthly_savings_goal`, `theme_preference`, `font_scale` and `ai_enabled` are absent for the
        // other reason: UC-22 gives an administrator no use case for any of them, and a field with no
        // use case is a field a client will depend on anyway.
        assertThat(fieldNames(schemas, "AdminUserResponse")).containsExactlyInAnyOrder(
                "id", "email", "fullName", "role", "status", "academicYear", "lastLoginAt",
                "createdAt");
        assertThat(fieldNames(schemas, "AdminUserResponse")).doesNotContain("passwordHash",
                "password_hash", "password", "tokenVersion", "token_version", "updatedAt",
                "updated_at", "monthlyAllowanceBaseline", "monthly_allowance_baseline",
                "monthlySavingsGoal", "monthly_savings_goal", "themePreference", "theme_preference",
                "fontScale", "font_scale", "aiEnabled", "ai_enabled", "emailVerifiedAt",
                "email_verified_at");

        // Both enums carry the column's own vocabulary, so an administrator reading a row and writing
        // a status sees one set of words. `UserRole` publishes two members because `role` is an ENUM
        // with two - the account list is the only place an administrator is named as one.
        assertThat(enumValues(schemas, "AdminUserResponse", "role"))
                .containsExactlyInAnyOrder("STUDENT", "ADMIN");
        assertThat(enumValues(schemas, "AdminUserResponse", "status"))
                .containsExactlyInAnyOrder("ACTIVE", "DISABLED");

        // The status write is one field, typed by the same enum the response publishes - and it is a
        // POST to a sub-path rather than a PATCH of the resource, because disabling also revokes
        // sessions and bumps `token_version` (BR-03). `userId` is absent for the usual reason: the
        // account is named by the path and the actor by the token, so a body-supplied id could only
        // be an ownership bypass.
        assertThat(fieldNames(schemas, "SetUserStatusRequest")).containsExactlyInAnyOrder("status");
        assertThat(fieldNames(schemas, "SetUserStatusRequest"))
                .doesNotContain("userId", "user_id", "id", "tokenVersion", "token_version");
        assertThat(enumValues(schemas, "SetUserStatusRequest", "status"))
                .isEqualTo(enumValues(schemas, "AdminUserResponse", "status"));

        // The reset response is one fixed message and no token. That is the whole of UC-22 B4's
        // disclosure policy: the link is delivered to the account owner's address and an administrator
        // who could read it off the screen would be able to take over the account (VĐ-06).
        assertThat(fieldNames(schemas, "AdminPasswordResetResponse"))
                .containsExactlyInAnyOrder("message");
        assertThat(fieldNames(schemas, "AdminPasswordResetResponse")).doesNotContain("token",
                "resetToken", "reset_token", "resetLink", "reset_link", "url", "expiresAt");

        // Nine fields, and it is the student module's own `CategoryResponse` type - one DTO over one
        // table, so the two cannot drift. `isDefault` is what tells the two populations apart, and a
        // request that could set it would be a request to move a row into or out of the shared scope,
        // which BR-06 refuses at the trigger.
        assertThat(fieldNames(schemas, "CategoryResponse")).contains("isDefault");
        for (String request : new String[] {"UpsertDefaultCategoryRequest",
                "UpdateDefaultCategoryRequest"}) {
            assertThat(fieldNames(schemas, request))
                    .as("%s must not let a client choose the row's scope or owner", request)
                    .doesNotContain("isDefault", "userId", "user_id", "createdBy", "created_by");
        }
        assertThat(fieldNames(schemas, "UpsertDefaultCategoryRequest")).containsExactlyInAnyOrder(
                "name", "type", "icon", "color", "description", "sortOrder", "isActive");
        assertThat(fieldNames(schemas, "UpdateDefaultCategoryRequest")).containsExactlyInAnyOrder(
                "name", "type", "icon", "color", "description", "sortOrder", "isActive");

        // Nine fields. `createdBy` is absent - the audit trail records who posted a notice, which is
        // what UC-22 B5 asks for - and `audience` is published because an administrator has to see
        // that a notice is ADMINS-only: the student dashboard receives `ALL` and `STUDENTS` only, so
        // an administrator looking at a notice students cannot see needs the field that explains it.
        assertThat(fieldNames(schemas, "AnnouncementResponse")).containsExactlyInAnyOrder(
                "id", "title", "body", "severity", "audience", "startsAt", "endsAt", "isActive",
                "createdAt");
        assertThat(fieldNames(schemas, "AnnouncementResponse")).doesNotContain("createdBy",
                "created_by", "updatedAt", "updated_at", "userId", "user_id");

        // The create request has six fields and no `isActive`: a notice is created active, and
        // deactivating it is the edit endpoint's single field, so accepting the flag here would be a
        // second way to express the same state at a moment when it has no meaning.
        assertThat(fieldNames(schemas, "CreateAnnouncementRequest")).containsExactlyInAnyOrder(
                "title", "body", "severity", "audience", "startsAt", "endsAt");
        assertThat(fieldNames(schemas, "CreateAnnouncementRequest")).doesNotContain("isActive",
                "createdBy", "id");
        // The edit is exactly `isActive`. Content is create-once in this build - `announcements` has
        // no content-update procedure, while `tip_templates` does - so a typo is corrected by posting
        // a new notice and withdrawing the old one. That asymmetry is deliberate: it is what keeps
        // every administrative write on a procedure, so `sp_require_admin` and the audit row cannot
        // be bypassed (OB-005).
        assertThat(fieldNames(schemas, "UpdateAnnouncementRequest"))
                .containsExactlyInAnyOrder("isActive");
        assertThat(fieldNames(schemas, "UpdateAnnouncementRequest")).doesNotContain("title", "body",
                "severity", "audience", "startsAt", "endsAt", "createdBy");

        // Both announcement enums publish the column's own values. `SUCCESS` and `ALL` are genuinely
        // reachable - the first is a severity the schema declares and nothing narrower would be
        // truthful, the second is an audience that reaches everybody - so a client switching on
        // either needs the full set.
        assertThat(enumValues(schemas, "AnnouncementResponse", "severity"))
                .containsExactlyInAnyOrder("INFO", "WARNING", "SUCCESS");
        assertThat(enumValues(schemas, "AnnouncementResponse", "audience"))
                .containsExactlyInAnyOrder("ALL", "STUDENTS", "ADMINS");
        assertThat(enumValues(schemas, "CreateAnnouncementRequest", "severity"))
                .isEqualTo(enumValues(schemas, "AnnouncementResponse", "severity"));
        assertThat(enumValues(schemas, "CreateAnnouncementRequest", "audience"))
                .isEqualTo(enumValues(schemas, "AnnouncementResponse", "audience"));

        // Seven fields, and `conditionParams` is the one deliberately withheld. The column exists and
        // is a JSON blob whose meaning the schema does not document; nothing in this build reads it,
        // so publishing it would offer a field neither side can interpret. It is recorded as a
        // follow-up rather than guessed at.
        assertThat(fieldNames(schemas, "TipTemplateResponse")).containsExactlyInAnyOrder(
                "id", "code", "conditionType", "titleTemplate", "bodyTemplate", "defaultPriority",
                "isActive");
        assertThat(fieldNames(schemas, "TipTemplateResponse")).doesNotContain("conditionParams",
                "condition_params", "createdBy", "created_by", "updatedAt", "updated_at");

        // The create request requires the code and the two text templates; the update request accepts
        // `code` so a full representation can be round-tripped, and the service refuses a different
        // one rather than letting the procedure ignore it silently. Neither request carries
        // `conditionParams`, which is what makes the column unreachable rather than merely unread.
        assertThat(fieldNames(schemas, "CreateTipTemplateRequest")).containsExactlyInAnyOrder(
                "code", "conditionType", "titleTemplate", "bodyTemplate", "defaultPriority",
                "isActive");
        assertThat(fieldNames(schemas, "UpdateTipTemplateRequest")).containsExactlyInAnyOrder(
                "code", "conditionType", "titleTemplate", "bodyTemplate", "defaultPriority",
                "isActive");
        for (String request : new String[] {"CreateTipTemplateRequest",
                "UpdateTipTemplateRequest"}) {
            assertThat(fieldNames(schemas, request))
                    .as("%s must not reach the column nothing in this build reads", request)
                    .doesNotContain("conditionParams", "condition_params", "createdBy",
                            "created_by");
        }
        assertThat(enumValues(schemas, "TipTemplateResponse", "conditionType"))
                .containsExactlyInAnyOrder("OVER_BUDGET", "NEAR_BUDGET", "CATEGORY_SPIKE",
                        "NO_BUDGET_SET", "SAVINGS_GOAL_AT_RISK", "LOW_SAVINGS_RATE", "GENERIC");

        // Five fields. `updatedBy` and `updatedAt` are absent: the audit trail records who changed a
        // threshold, when, and what the value was before, which is a stronger answer than a per-row
        // copy and leaves one authority rather than two.
        assertThat(fieldNames(schemas, "SystemSettingResponse")).containsExactlyInAnyOrder(
                "key", "value", "valueType", "description", "adjustable");
        assertThat(fieldNames(schemas, "SystemSettingResponse")).doesNotContain("updatedBy",
                "updated_by", "updatedAt", "updated_at", "settingKey", "settingValue");
        // The update is one field, sent as text: `setting_value` is VARCHAR(255), and typing this as
        // a number would make the API unable to carry any future key that is not one while forcing
        // the shape check into two places.
        assertThat(fieldNames(schemas, "UpdateThresholdRequest"))
                .containsExactlyInAnyOrder("value");
        assertThat(fieldNames(schemas, "UpdateThresholdRequest"))
                .doesNotContain("key", "settingKey", "valueType");

        // Ten aggregate fields, all of them sums or counts across the whole user base. There is no
        // identifier of any kind - which is the property that makes publishing money defensible here
        // and the reason each field is named rather than the set being checked loosely.
        assertThat(fieldNames(schemas, "AdminUsageStatsResponse")).containsExactlyInAnyOrder(
                "totalStudents", "activeStudents", "disabledStudents", "activeUsers30d",
                "totalTransactions", "totalExpenseLogged", "totalIncomeLogged", "totalBudgets",
                "totalTipsGenerated", "totalInsightsGenerated");
        assertThat(fieldNames(schemas, "AdminUsageStatsResponse"))
                .doesNotContain("userId", "user_id", "email", "fullName", "studentId",
                        "student_id");

        // Seven fields. `scope` is what makes the ranking readable - a shared default and a student's
        // own category can share a name and mean different things - and `distinctUsers` is what
        // separates a category many students use from one student using it heavily.
        assertThat(fieldNames(schemas, "AdminTopCategoryResponse")).containsExactlyInAnyOrder(
                "categoryId", "categoryName", "type", "scope", "txnCount", "totalAmount",
                "distinctUsers");
        assertThat(fieldNames(schemas, "AdminTopCategoryResponse"))
                .doesNotContain("userId", "user_id", "email", "fullName", "categoryIcon",
                        "categoryColor");
        assertThat(enumValues(schemas, "AdminTopCategoryResponse", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");

        // No administration endpoint takes a body it should not, and no module 12 surface has a
        // schema. A `CreateInsightRequest` or an `AdminAnomalyResponse` would name a capability that
        // is locked, and a schema is how such a capability would first become visible in the
        // contract - which is why the guard is here rather than only in the route list.
        for (String forbidden : new String[] {"CreateInsightRequest", "UpdateInsightRequest",
                "AdminInsightResponse", "AdminAnomalyResponse", "AdminAiResponse",
                "CreateAuditLogRequest", "AdminAuditResponse", "DeleteUserRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: it names a capability this module does not have",
                            forbidden)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Section 13 phase 7: the recent-activity contract has the documented shape")
    void recentActivitySchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // Eight fields, and the entry is the transaction's own detail plus the act: UC-26's list says
        // which of a student's transactions they opened or changed, so the client renders a row from
        // this one response rather than resolving a transaction id per entry. `userId` is absent for
        // the usual reason - the query already applied ownership (BR-02) - and it matters here because
        // the trail is a record of one student's reading habits.
        assertThat(fieldNames(schemas, "RecentActivityResponse")).containsExactlyInAnyOrder(
                "transactionId", "action", "occurredAt", "categoryId", "categoryType", "amount",
                "description", "txnDate");
        assertThat(fieldNames(schemas, "RecentActivityResponse"))
                .doesNotContain("id", "userId", "user_id", "encryptedDescription",
                        "encrypted_description", "isDeleted", "is_deleted");

        // The wrapper carries the limit the server actually applied, which is why it exists: UC-26
        // asks for "the last few" rather than a fixed number, so the size is the caller's request and
        // the response has to report what it did with it.
        assertThat(fieldNames(schemas, "RecentActivityListResponse"))
                .containsExactlyInAnyOrder("limit", "entries");
        assertThat(fieldNames(schemas, "RecentActivityListResponse"))
                .doesNotContain("userId", "user_id", "total", "page", "offset");

        // Two fields, and both are what the row is keyed by: `uk_recent` is (user_id,
        // transaction_id, action), so naming the transaction and the action names the entry. There is
        // no `occurredAt` in the request because the time is the database's `NOW()` - a client-settable
        // timestamp would let a student write their own history - and no `userId`, since the caller is
        // the token.
        assertThat(fieldNames(schemas, "RecordRecentActivityRequest"))
                .containsExactlyInAnyOrder("transactionId", "action");
        assertThat(fieldNames(schemas, "RecordRecentActivityRequest"))
                .doesNotContain("userId", "user_id", "id", "occurredAt", "occurred_at",
                        "categoryId", "amount");

        // The action is published as its member names, and the request and the response must advertise
        // the same two because both are typed by the one `RecentAction`. The vocabulary is the
        // column's own, so it is discoverable from Swagger without reading the database.
        assertThat(enumValues(schemas, "RecordRecentActivityRequest", "action"))
                .containsExactlyInAnyOrder("VIEWED", "EDITED");
        assertThat(enumValues(schemas, "RecentActivityResponse", "action"))
                .isEqualTo(enumValues(schemas, "RecordRecentActivityRequest", "action"));

        // `description` is optional - a transaction without one omits the field rather than sending
        // null - so a client must be able to tell "no description" from an empty one. The schema
        // publishes it rather than hiding it, because the encrypted column decrypts to a field the
        // student wrote and the response carries the plaintext.
        assertThat(schemas.at("/RecentActivityResponse/properties/description").isMissingNode())
                .isFalse();

        // No schema here could clear the trail or address one entry: UC-26 gives a student no way to
        // erase their own history, and an entry has no identifier of its own beyond the pair above.
        for (String forbidden : new String[] {"ClearRecentActivityRequest", "DeleteRecentActivityRequest",
                "RecentActivityIdResponse", "UpdateRecentActivityRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: UC-26 offers no such operation", forbidden)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Section 13 phase 7: the anomaly contract has the documented shape")
    void anomalySchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // Ten fields, and the entry is a transaction's own detail plus the mark: UC-24 shows a student
        // which of their records look wrong, so the row has to render the record itself - the amount,
        // the date, the category - rather than only its id. `userId` is absent for the usual reason
        // (BR-02 already narrowed the query), which matters here because the mark is a statement about
        // one student's spending pattern.
        assertThat(fieldNames(schemas, "FlaggedTransactionResponse")).containsExactlyInAnyOrder(
                "transactionId", "categoryId", "categoryName", "categoryType", "amount", "txnDate",
                "description", "isFlagged", "flagType", "flagNote");
        assertThat(fieldNames(schemas, "FlaggedTransactionResponse"))
                .doesNotContain("id", "userId", "user_id", "encryptedDescription",
                        "encrypted_description", "isDeleted", "is_deleted", "categorySpend");

        // The wrapper carries the limit the server applied, for the reason the recent-activity wrapper
        // does: the size is the caller's request and the response reports what it did with it.
        assertThat(fieldNames(schemas, "FlaggedTransactionListResponse"))
                .containsExactlyInAnyOrder("limit", "entries");
        assertThat(fieldNames(schemas, "FlaggedTransactionListResponse"))
                .doesNotContain("userId", "user_id", "total", "page", "offset");

        // The scan reports four counts and the list it left behind. `examined` is the denominator -
        // flagged + cleared + unchanged - and publishing it is what lets a client say "nothing looks
        // wrong" only after the server actually looked.
        assertThat(fieldNames(schemas, "AnomalyScanResponse")).containsExactlyInAnyOrder(
                "examined", "flagged", "cleared", "unchanged", "entries");
        assertThat(fieldNames(schemas, "AnomalyScanResponse"))
                .doesNotContain("userId", "user_id", "scannedAt", "durationMs");

        // The mark is a published fact and never an accepted one. A client that could send `flagType`
        // or `isFlagged` could mark its own records, which is exactly what UC-24 withholds: the check
        // runs on the server over the student's own data and the client reads the result. So no schema
        // here may accept a mark, and none may name a per-record action.
        for (String forbidden : new String[] {"FlagTransactionRequest", "SetFlagRequest",
                "AnomalyScanRequest", "ClearAnomaliesRequest", "UpdateAnomalyRequest",
                "AnomalyIdResponse"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: UC-24 gives a client no way to set its own marks", forbidden)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Section 13 phase 7: the forecast contract has the documented shape")
    void forecastSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // Six top-level fields, and the split is the point: a month label for what the student is in
        // the middle of, one for what is being predicted, how many months the estimate rests on, the
        // months themselves as evidence, and then the two figures. `userId` is absent for the usual
        // reason (BR-02 already narrowed the read), and `email` with it: every figure came from the
        // caller's own token, so a response naming its owner would publish an identifier the forecast
        // has no use for.
        assertThat(fieldNames(schemas, "ForecastResponse")).containsExactlyInAnyOrder(
                "nextMonth", "currentMonth", "basedOnMonths", "recentMonths", "currentMonthTotals",
                "projected");
        assertThat(fieldNames(schemas, "ForecastResponse"))
                .doesNotContain("userId", "user_id", "email", "month", "windowMonths",
                        "window_months", "generatedAt", "asOf");

        // The evidence month is a figure the student can check, so it carries both totals and the net
        // rather than only the net: a client rendering "you spent 200.00 in August" needs the expense,
        // and deriving it from the net would be the client re-doing the arithmetic. Springdoc publishes
        // a nested record under its own name (as it does for `ApiError.FieldError`), so the block is
        // checked by that name rather than by a `ForecastResponse_` prefix.
        assertThat(fieldNames(schemas, "ForecastMonthResponse"))
                .containsExactlyInAnyOrder("periodMonth", "income", "expense", "net");

        // The month in progress is the same three figures and no month label of its own: its label is
        // `currentMonth` on the parent, and repeating it per block would be two names for one fact.
        assertThat(fieldNames(schemas, "CurrentMonthTotalsResponse"))
                .containsExactlyInAnyOrder("income", "expense", "net");

        // The projection publishes income, expense and savings - and savings rather than a "net" that
        // matched the other two blocks' naming, because it is the only one of the three the student is
        // being asked to act on. It can be negative, which the schema's description says.
        assertThat(fieldNames(schemas, "ProjectedMonthResponse"))
                .containsExactlyInAnyOrder("income", "expense", "savings");

        // The projection is a computed fact and never an accepted one. UC-25 gives a client no way to
        // supply a month, a window or an estimate: the month is the server's clock and the window is a
        // documented judgement, so no schema here may carry a request of any shape.
        for (String forbidden : new String[] {"ForecastRequest", "CreateForecastRequest",
                "UpdateForecastRequest", "ProjectionRequest", "ForecastMonthRequest",
                "SetForecastRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: UC-25 accepts no input", forbidden)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Section 13 phase 7: the categorisation contract has the documented shape")
    void categorisationSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // Eight fields, and the split is the design. `transactionId` says which record the answer is
        // about; `source` says who reached the conclusion, which BR-13 makes part of the answer rather
        // than metadata; the next four are the proposal itself and are absent together when there is
        // none; `reason` is the provider's own phrase for an AI proposal; and `learned` is what the call
        // left stored. `userId` is absent for the usual reason - the read already narrowed on it - and it
        // matters more here than elsewhere: a response naming its owner would publish the identifier in
        // the same object as the student's description-derived keyword.
        assertThat(fieldNames(schemas, "CategorySuggestionResponse")).containsExactlyInAnyOrder(
                "transactionId", "source", "categoryId", "categoryName", "type", "confidence",
                "reason", "learned");
        assertThat(fieldNames(schemas, "CategorySuggestionResponse"))
                .doesNotContain("userId", "user_id", "email", "description", "categoryRules",
                        "candidates", "provider", "model", "overridden", "suggestedCategoryId");

        // The learned mapping is reported as four facts and no more. `keyword` is the student's own
        // normalised description - the thing the next match compares against, and text they typed
        // themselves, so publishing it discloses nothing they did not supply. `hitCount`, `confidence`
        // and `lastUsedAt` are deliberately absent: the column exists, nothing maintains it, and
        // publishing a counter that never moves would describe the learning as something it is not.
        assertThat(fieldNames(schemas, "LearnedCategoryRuleResponse")).containsExactlyInAnyOrder(
                "keyword", "categoryId", "categoryName", "source");
        assertThat(fieldNames(schemas, "LearnedCategoryRuleResponse"))
                .doesNotContain("ruleId", "id", "userId", "user_id", "hitCount", "hit_count",
                        "confidence", "lastUsedAt", "last_used_at", "matchMode", "match_mode",
                        "createdAt", "updatedAt");

        // Section 7.2's field-level guarantee, restated for this schema because the cross-cutting scan
        // above cannot reach it. That scan walks the schemas a response `$ref`s, and this record is
        // published INLINE inside `CategorySuggestionResponse` - so its name never appears as a
        // reference and a field added here would slip past that check. It is the schema where the
        // guarantee matters most, because `keyword` is the one place free text the student typed leaves
        // the server, and a sensitive field beside it would be published in the same object.
        assertThat(fieldNames(schemas, "LearnedCategoryRuleResponse"))
                .doesNotContain("password", "passwordHash", "password_hash", "resetToken",
                        "reset_token", "sessionToken", "session_token", "refreshToken",
                        "refresh_token", "tokenVersion", "token_version", "email");

        // `source` names where a proposal came from, and all three members are published - including
        // NONE, which is a result rather than an error. This is the field that lets a client say "we
        // remember how you filed this" instead of implying a model said it.
        assertThat(enumValues(schemas, "CategorySuggestionResponse", "source"))
                .containsExactlyInAnyOrder("NONE", "RULE", "AI");

        // The learning's own vocabulary, mirroring `category_rules.source`. IMPORT is a member because
        // the column has it and UC-11 owns the case that writes it; this build never produces it, and
        // the enum is published whole rather than narrowed to what one module happens to set.
        assertThat(enumValues(schemas, "LearnedCategoryRuleResponse", "source"))
                .containsExactlyInAnyOrder("ACCEPTED", "OVERRIDE", "IMPORT");

        // The request carries one field: which record to categorise. No category may be sent, because
        // the mapping learned is derived from the category the record is actually in - a client able to
        // supply one could teach the system a filing that never happened. No description either: it is
        // read from the record, so that the keyword is the text the student actually saved.
        assertThat(fieldNames(schemas, "SuggestCategoryRequest"))
                .containsExactlyInAnyOrder("transactionId");

        // UC-08 gives a client no way to state a conclusion. There is no request that sets a suggested
        // category, a confidence or a rule - those are what the system computes and learns - and there
        // is no request carrying the description, for the reason above. The names checked here are the
        // shapes such a route would need; none may exist.
        for (String forbidden : new String[] {"CategoriseRequest", "CategorizeRequest",
                "CategorySuggestionRequest", "CreateCategoryRuleRequest", "CategoryRuleRequest",
                "UpdateCategoryRuleRequest", "SetSuggestedCategoryRequest", "CategoriseTransactionRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: UC-08 gives a client no way to state a conclusion",
                            forbidden)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Section 13 phase 7: the insight contract has the documented shape")
    void insightSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        // Ten fields, and the split is the design. `periodMonth` names which month is being described,
        // so a client holding several answers can tell them apart; the three figures are what the
        // database computed; `summary` and `advice` are the prose; `generatedBy` and `model` say who
        // wrote it, which BR-13 makes part of the answer rather than metadata; `flaggedCategories` is
        // the evidence for "something was unusual"; and `generatedAt` says whether the snapshot
        // predates a correction the student has since made. `userId` is absent for the usual reason -
        // the read already narrowed on it - and it matters more here than anywhere else in module 12,
        // because this response is a whole month's financial position rather than a slice of it, so an
        // owner identifier beside it would be published next to the most sensitive figures in the
        // system.
        assertThat(fieldNames(schemas, "MonthlyInsightResponse")).containsExactlyInAnyOrder(
                "periodMonth", "totalIncome", "totalExpense", "netAmount", "summary", "advice",
                "generatedBy", "model", "flaggedCategories", "generatedAt");
        assertThat(fieldNames(schemas, "MonthlyInsightResponse"))
                .doesNotContain("userId", "user_id", "email", "id", "insightId",
                        "status", "errorMessage", "error_message", "flagged_categories");

        // The picker list is one array under one key, for the reason the tips list is: a top-level JSON
        // array cannot gain a field without breaking every client reading it as a list.
        assertThat(fieldNames(schemas, "InsightMonthsResponse")).containsExactlyInAnyOrder("months");
        assertThat(fieldNames(schemas, "InsightMonthsResponse"))
                .doesNotContain("userId", "user_id", "total", "page", "offset");

        // The flagged block is five fields and its shape is the stored array's own, so the comparison a
        // student sees is the one that made the database flag the category rather than one recomputed
        // here. Springdoc publishes a nested record under its own name (as it does for
        // `ForecastMonthResponse` and `ApiError.FieldError`), so the block is checked by that name
        // rather than by a `MonthlyInsightResponse_` prefix.
        assertThat(fieldNames(schemas, "FlaggedCategoryResponse")).containsExactlyInAnyOrder(
                "categoryId", "categoryName", "currentTotal", "baselineAvg", "pctChange");
        assertThat(fieldNames(schemas, "FlaggedCategoryResponse"))
                .doesNotContain("userId", "user_id", "isSpike", "is_spike", "baselineMonths",
                        "baseline_months", "periodMonth", "type");

        // Section 7.2's field-level guarantee, restated for this schema because the cross-cutting scan
        // above cannot reach it. That scan walks the schemas a response `$ref`s, and this record is
        // published INLINE inside `MonthlyInsightResponse` - so its name never appears as a reference
        // and a field added here would slip past that check.
        assertThat(fieldNames(schemas, "FlaggedCategoryResponse"))
                .doesNotContain("password", "passwordHash", "password_hash", "resetToken",
                        "reset_token", "sessionToken", "session_token", "refreshToken",
                        "refresh_token", "tokenVersion", "token_version", "email");

        // Who wrote the prose, and all three members are published - including MANUAL, which the column
        // has and this build never writes. This is the field a client switches on to label the text as
        // a suggestion rather than as financial advice (BR-13), so narrowing it to the two values this
        // build produces would leave a client unable to render a row written by neither path.
        assertThat(enumValues(schemas, "MonthlyInsightResponse", "generatedBy"))
                .containsExactlyInAnyOrder("AI", "RULE_BASED", "MANUAL");

        // UC-17 gives a client no way to state what an insight says. There is no request that supplies
        // a month of its own choosing with prose, no request that sets a figure, a flagged category or
        // a `generatedBy`, and none that clears the stored month - the figures are the student's own
        // records and the prose is composed from them. The only input the feature accepts is the
        // optional `month` query parameter, which is a parameter rather than a schema. The names checked
        // here are the shapes such a route would need; none may exist.
        for (String forbidden : new String[] {"GenerateInsightRequest", "CreateInsightRequest",
                "UpdateInsightRequest", "InsightRequest", "FlagCategoryRequest",
                "SetInsightRequest", "DeleteInsightRequest", "InsightIdResponse"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: UC-17 gives a client no way to author an insight", forbidden)
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** Reads the live OpenAPI document the application serves, exactly as a client would. */
    private JsonNode openApiDocument() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private static Set<String> fieldNames(JsonNode schemas, String schemaName) {
        JsonNode properties = schemas.at("/" + schemaName + "/properties");
        assertThat(properties.isObject())
                .as("schema %s must exist with properties", schemaName)
                .isTrue();
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        properties.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
        return fields.keySet();
    }

    /** The permitted values of an enum-typed property, as published in the document. */
    private static Set<String> enumValues(JsonNode schemas, String schemaName, String propertyName) {
        JsonNode values = schemas.at("/" + schemaName + "/properties/" + propertyName + "/enum");
        assertThat(values.isArray())
                .as("%s.%s must publish its enum members", schemaName, propertyName)
                .isTrue();
        Set<String> members = new TreeSet<>();
        values.forEach(value -> members.add(value.asText()));
        return members;
    }

    /**
     * Walks a response object and records every {@code $ref} it reaches, so a schema is only
     * treated as a response schema if some success or error response actually points at it.
     */
    private static void collectSchemaReferences(JsonNode node, Set<String> found) {
        if (node.isObject()) {
            JsonNode reference = node.get("$ref");
            if (reference != null) {
                String value = reference.asText();
                found.add(value.substring(value.lastIndexOf('/') + 1));
            }
            node.fields().forEachRemaining(entry -> collectSchemaReferences(entry.getValue(), found));
        } else if (node.isArray()) {
            node.forEach(element -> collectSchemaReferences(element, found));
        }
    }

    /** The subset of OpenAPI path keys that name an HTTP operation rather than a parameter. */
    private static boolean isOperation(String field) {
        return switch (field) {
            case "get", "post", "put", "patch", "delete", "head", "options", "trace" -> true;
            default -> false;
        };
    }
}
