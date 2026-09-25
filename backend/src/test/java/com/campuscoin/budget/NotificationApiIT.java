package com.campuscoin.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * UC-14 over the real HTTP stack: reading budget alerts and marking them read.
 *
 * <p><b>The fixtures are transactions, and that is the point of the suite.</b> This module writes no
 * notification: every row here is produced by {@code sp_check_budget_alerts}, which
 * {@code trg_transactions_after_insert} calls when a record crosses a threshold. So a test that wants
 * a message sets a limit and then spends against it - and in doing so proves the flow UC-14 actually
 * describes, rather than inserting a notification and testing the read path against a row no part of
 * the system would have written. The one exception is the ownership tests, which need a second
 * student's message and raise it the same way, by spending.
 *
 * <p><b>What this suite cannot assert through the API, and reads from MySQL instead.</b> The alert log
 * is not published - it is the trigger's durable record, not a client contract - so "how many alerts
 * fired" and "at what percentage" are read from {@code budget_alert_log}. That is what separates
 * BR-12's guarantee (one alert per threshold per budget per month) from the observable symptom (one
 * message), and a test that only counted messages could not tell the rule holding from the message
 * happening to be unique.
 */
class NotificationApiIT extends AbstractBudgetApiIT {

    // ==================================================================
    //  UC-14: where the alerts come from
    // ==================================================================

    @Test
    @DisplayName("UC-14/BR-12: a record crossing the near threshold raises one alert and one message")
    void crossingTheNearThresholdRaisesOneAlert() throws Exception {
        // The whole of UC-14's first branch, end to end: set a limit, spend past 80% of it, and read
        // back exactly what was raised. Every field of the message is asserted literally because the
        // procedure composes it, and a client renders it verbatim.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "10.00", thisMonth()).get("id").asLong();

        createTransaction(token, categoryId, "8.00", today(), "at eighty percent");

        assertThat(alertRowsFor(budgetId)).containsExactly("NEAR|80.00");

        List<NotificationRow> rows = notificationsFor(userId);
        assertThat(rows).hasSize(1);
        NotificationRow row = rows.get(0);
        assertThat(row.type()).isEqualTo("BUDGET_NEAR");
        assertThat(row.title()).isEqualTo("Approaching budget limit: " + DEFAULT_EXPENSE_NAME);
        assertThat(row.body())
                .isEqualTo("You have used 80.00% of your " + DEFAULT_EXPENSE_NAME
                        + " budget (8.00 of 10.00).");
        // The message points at the screen that can act on it, and at the limit it is about, so a
        // client can open the right budget rather than the budgets list in general.
        assertThat(row.linkUrl()).isEqualTo("/budgets");
        assertThat(row.refEntityType()).isEqualTo("BUDGET");
        assertThat(row.refEntityId()).isEqualTo(budgetId);
        // Raised unread, with no timestamp - ck_notif_read forbids the other combination.
        assertThat(row.isRead()).isFalse();
        assertThat(row.readAt()).isNull();
    }

    @Test
    @DisplayName("UC-14/BR-12: passing the limit raises a second alert, and staying past it raises "
            + "no third")
    void crossingTheExceededThresholdRaisesOneAlert() throws Exception {
        // Both branches, and then the rule that bounds them. The near threshold fires at 80%, the
        // exceeded threshold at 100%, and a further record that pushes the percentage higher still
        // produces nothing new - because uk_alert_budget_threshold (budget_id, threshold_type) has
        // already recorded that this budget crossed at this level. A student is warned once, not on
        // every purchase after that.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "10.00", thisMonth()).get("id").asLong();

        createTransaction(token, categoryId, "8.00", today(), "over the near threshold");
        createTransaction(token, categoryId, "3.00", today(), "over the limit");

        List<String> alerts = alertRowsFor(budgetId);
        assertThat(alerts).containsExactly("NEAR|80.00", "EXCEEDED|110.00");

        List<NotificationRow> rows = notificationsFor(userId);
        assertThat(rows).hasSize(2);
        NotificationRow exceeded = rows.get(1);
        assertThat(exceeded.type()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(exceeded.title()).isEqualTo("Budget exceeded: " + DEFAULT_EXPENSE_NAME);
        assertThat(exceeded.body())
                .isEqualTo("You have spent 11.00 of 10.00 (110.00%) on " + DEFAULT_EXPENSE_NAME
                        + ".");

        // Two more records, at 120% and 150%, which cross no new threshold.
        createTransaction(token, categoryId, "1.00", today(), "still over");
        createTransaction(token, categoryId, "3.00", today(), "further over");

        assertThat(alertRowsFor(budgetId)).containsExactly("NEAR|80.00", "EXCEEDED|110.00");
        assertThat(notificationCountFor(userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("UC-14/BR-09: moving a record to the trash does not withdraw the warning")
    void deletingTheRecordDoesNotWithdrawTheAlert() throws Exception {
        // BR-09 stops a deleted record from counting toward the limit, so the budget drops back to
        // ON_TRACK. The notification stays, and that is correct: the student was told they had
        // crossed a threshold, and they had. Withdrawing the message would be rewriting what
        // happened; what changes is the current state, which the budget screen now reports as
        // ON_TRACK.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        Long budgetId = createBudget(token, categoryId, "10.00", thisMonth()).get("id").asLong();
        Long transactionId = createTransaction(token, categoryId, "9.00", today(), "then removed");

        assertThat(notificationCountFor(userId)).isEqualTo(1);

        assertThat(send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + transactionId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(body(send(HttpMethod.GET, BUDGETS_URL + "/" + budgetId, token, null))
                .get("consumptionStatus").asText()).isEqualTo("ON_TRACK");
        assertThat(notificationCountFor(userId)).isEqualTo(1);
        assertThat(alertRowsFor(budgetId)).containsExactly("NEAR|90.00");
    }

    @Test
    @DisplayName("UC-14: spending in a category with no limit raises nothing")
    void spendingWithoutALimitRaisesNothing() throws Exception {
        // sp_check_budget_alerts returns immediately when no budget row matches the category and
        // month. Worth pinning because the alternative - a message per expense - would make the
        // list useless, and because it is the boundary that makes "set a limit" the action that
        // turns alerting on.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        createTransaction(token, categoryId, "500.00", today(), "no limit set");

        assertThat(notificationCountFor(userId)).isZero();
    }

    // ==================================================================
    //  UC-14: reading the list
    // ==================================================================

    @Test
    @DisplayName("UC-14: a student with no messages gets an empty list")
    void emptyStateIsAnEmptyArray() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response = send(HttpMethod.GET, NOTIFICATIONS_URL, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = body(response);
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    @DisplayName("UC-14: a message comes back with exactly the documented fields and no owner")
    void aMessageHasTheDocumentedShape() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        createBudget(token, categoryId, "10.00", thisMonth());
        createTransaction(token, categoryId, "8.00", today(), "raise the alert");

        JsonNode list = body(send(HttpMethod.GET, NOTIFICATIONS_URL, token, null));

        assertThat(list).hasSize(1);
        JsonNode message = list.get(0);
        assertThat(fieldNamesOf(message)).isSubsetOf(DOCUMENTED_NOTIFICATION_FIELDS);
        assertThat(fieldNamesOf(message)).contains("id", "type", "title", "body", "linkUrl",
                "refEntityType", "refEntityId", "isRead", "createdAt");
        assertThat(fieldNamesOf(message)).doesNotContain("userId");
        // An unread message carries no readAt at all, rather than a null one - ck_notif_read's
        // "unread has no timestamp" is visible in the JSON, not merely implied by it.
        assertThat(fieldNamesOf(message)).doesNotContain("readAt");
        assertThat(message.get("isRead").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("UC-14: the list is newest first, so the latest warning is the first row")
    void theListIsNewestFirst() throws Exception {
        // The order the screen reads in. The tie-break is the id, because both alerts here are
        // written with NOW() inside transactions that may well commit within the same second - so
        // the test asserts an order that has to hold whichever way that lands.
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        createBudget(token, categoryId, "10.00", thisMonth());

        createTransaction(token, categoryId, "8.00", today(), "near");
        createTransaction(token, categoryId, "3.00", today(), "exceeded");

        JsonNode list = body(send(HttpMethod.GET, NOTIFICATIONS_URL, token, null));

        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("type").asText()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(list.get(1).get("type").asText()).isEqualTo("BUDGET_NEAR");
        assertThat(list.get(0).get("id").asLong()).isGreaterThan(list.get(1).get("id").asLong());
    }

    @Test
    @DisplayName("UC-14: reading one message returns the same row the list would")
    void readingOneMessageMatchesTheList() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long notificationId = raiseNearAlert(token, userId);

        JsonNode single = body(send(HttpMethod.GET, NOTIFICATIONS_URL + "/" + notificationId, token,
                null));

        assertThat(single.get("id").asLong()).isEqualTo(notificationId);
        assertThat(single.get("type").asText()).isEqualTo("BUDGET_NEAR");
        assertThat(single.get("title").asText())
                .isEqualTo("Approaching budget limit: " + DEFAULT_EXPENSE_NAME);
    }

    @Test
    @DisplayName("UC-14: reading one message does not mark it read")
    void readingOneMessageDoesNotMarkItRead() throws Exception {
        // Opening a message and acknowledging it are separate actions, and a client that merely
        // links to one must not silently clear the unread marker - the same reason the read is a
        // POST of its own rather than a field the caller sets.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long notificationId = raiseNearAlert(token, userId);

        send(HttpMethod.GET, NOTIFICATIONS_URL + "/" + notificationId, token, null);
        send(HttpMethod.GET, NOTIFICATIONS_URL + "/" + notificationId, token, null);

        assertThat(notificationsFor(userId).get(0).isRead()).isFalse();
        assertThat(body(send(HttpMethod.GET, NOTIFICATIONS_URL + "/" + notificationId, token, null))
                .get("isRead").asBoolean()).isFalse();
    }

    // ==================================================================
    //  UC-14: marking read
    // ==================================================================

    @Test
    @DisplayName("UC-14 B4: marking read sets the read flag and the timestamp together")
    void markingReadSetsFlagAndTimestampTogether() throws Exception {
        // ck_notif_read requires the pair, so both must be written in the one statement the
        // procedure makes. Read from the database as well as from the response, because the
        // response is built by re-reading the row and would look right even if Hibernate had written
        // it without going through the procedure.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long notificationId = raiseNearAlert(token, userId);

        ResponseEntity<String> response = send(HttpMethod.POST,
                NOTIFICATIONS_URL + "/" + notificationId + "/read", token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode marked = body(response);
        assertThat(marked.get("id").asLong()).isEqualTo(notificationId);
        assertThat(marked.get("isRead").asBoolean()).isTrue();
        assertThat(marked.get("readAt").isNull()).isFalse();

        NotificationRow row = notificationsFor(userId).get(0);
        assertThat(row.isRead()).isTrue();
        assertThat(row.readAt()).isNotNull();
        assertThat(booleanInDatabase(notificationId, "notifications", "is_read")).isTrue();
    }

    @Test
    @DisplayName("UC-14 B5: marking an already-read message is answered, not refused, and keeps the "
            + "first timestamp")
    void markingReadTwiceKeepsTheFirstTimestamp() throws Exception {
        // A retry, or two devices marking the same message, must settle on the first time it was
        // read rather than pushing the timestamp forward with every attempt - the procedure's
        // `AND is_read = 0` is what makes that true, and this is the test that would fail if someone
        // replaced it with a Hibernate UPDATE.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long notificationId = raiseNearAlert(token, userId);
        String readUrl = NOTIFICATIONS_URL + "/" + notificationId + "/read";

        String firstReadAt = body(send(HttpMethod.POST, readUrl, token, null)).get("readAt").asText();

        ResponseEntity<String> second = send(HttpMethod.POST, readUrl, token, null);

        // Not 409, not 404: the end state the caller asked for is true, so the answer is the
        // notification.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(second).get("isRead").asBoolean()).isTrue();
        assertThat(instantOf(body(second).get("readAt").asText())).isEqualTo(instantOf(firstReadAt));
        assertThat(instantOf(notificationsFor(userId).get(0).readAt()))
                .isEqualTo(instantOf(firstReadAt));

        // And the database still holds exactly one timestamp, the first one.
        assertThat(booleanInDatabase(notificationId, "notifications", "is_read")).isTrue();
    }

    @Test
    @DisplayName("UC-14: marking one message read leaves the others unread")
    void markingOneReadLeavesTheOthersUnread() throws Exception {
        // Marking the *older* message, so "leaves the others alone" is asserted against a row that
        // is newer rather than older - the direction a naive "mark everything up to here" would
        // wrongly clear.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        createBudget(token, categoryId, "10.00", thisMonth());
        createTransaction(token, categoryId, "8.00", today(), "near");
        createTransaction(token, categoryId, "3.00", today(), "exceeded");

        JsonNode list = body(send(HttpMethod.GET, NOTIFICATIONS_URL, token, null));
        Long nearId = list.get(1).get("id").asLong();
        Long exceededId = list.get(0).get("id").asLong();

        send(HttpMethod.POST, NOTIFICATIONS_URL + "/" + nearId + "/read", token, null);

        // notificationsFor orders by id, so index 0 is the alert raised first.
        List<NotificationRow> rows = notificationsFor(userId);
        assertThat(rows.get(0).type()).isEqualTo("BUDGET_NEAR");
        assertThat(rows.get(0).isRead()).isTrue();
        assertThat(rows.get(1).type()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(rows.get(1).isRead()).isFalse();
        assertThat(rows.get(1).readAt()).isNull();
        assertThat(booleanInDatabase(exceededId, "notifications", "is_read")).isFalse();
    }

    @Test
    @DisplayName("UC-14: there is no endpoint that marks a message unread")
    void thereIsNoUnreadEndpoint() throws Exception {
        // The transition is one-way. A message that has been seen has been seen, and the absence of
        // the route is what enforces it - so this asserts the absence rather than a refusal. Which
        // 4xx the framework picks depends on how it resolves the path - 405 when the path is known
        // and the method is not, 404 when nothing is mapped there - so the assertion is on the class
        // of answer, as module 5's equivalent does, and what matters is that neither is a success.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long notificationId = raiseNearAlert(token, userId);
        send(HttpMethod.POST, NOTIFICATIONS_URL + "/" + notificationId + "/read", token, null);

        assertThat(send(HttpMethod.DELETE, NOTIFICATIONS_URL + "/" + notificationId + "/read", token,
                null).getStatusCode().is4xxClientError())
                .as("there must be no route that marks a notification unread")
                .isTrue();
        assertThat(send(HttpMethod.PUT, NOTIFICATIONS_URL + "/" + notificationId, token,
                Map.of("isRead", false)).getStatusCode().is4xxClientError())
                .as("a notification must not be writable through a request body")
                .isTrue();
        // And the message is still read, so nothing above moved it.
        assertThat(notificationsFor(userId).get(0).isRead()).isTrue();
    }

    // ==================================================================
    //  Ownership and roles
    // ==================================================================

    @Test
    @DisplayName("UC-14: a student cannot see a message addressed to someone else")
    void theListShowsOnlyTheCallersMessages() throws Exception {
        // The list is filtered by the token's account, so another student's message is not merely
        // refused - it is not there. That matters more here than on a numeric resource: the title
        // and body are readable prose about the other student's spending.
        String owner = loginNewStudent();
        String other = loginNewStudent();
        Long ownerId = userIdOf(owner);
        raiseNearAlert(owner, ownerId);

        assertThat(notificationCountFor(ownerId)).isEqualTo(1);
        assertThat(body(send(HttpMethod.GET, NOTIFICATIONS_URL, other, null))).isEmpty();
    }

    @Test
    @DisplayName("Section 7.5: another student's message is unreachable and indistinguishable from "
            + "a missing one")
    void anotherStudentsMessageIsUnreachable() throws Exception {
        String owner = loginNewStudent();
        String intruder = loginNewStudent();
        Long ownerId = userIdOf(owner);
        Long notificationId = raiseNearAlert(owner, ownerId);

        HttpMethod[] reads = {HttpMethod.GET, HttpMethod.POST};
        for (HttpMethod method : reads) {
            String url = method == HttpMethod.GET
                    ? NOTIFICATIONS_URL + "/" + notificationId
                    : NOTIFICATIONS_URL + "/" + notificationId + "/read";
            ResponseEntity<String> response = send(method, url, intruder, null);
            assertThat(response.getStatusCode()).as("%s must not reach another student's message",
                            method)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        // The refusal is the same answer a message that does not exist receives, so the endpoint
        // cannot be used to discover which identifiers exist.
        JsonNode foreign = body(send(HttpMethod.GET, NOTIFICATIONS_URL + "/" + notificationId,
                intruder, null));
        JsonNode missing = body(send(HttpMethod.GET, NOTIFICATIONS_URL + "/999999999", intruder,
                null));
        assertThat(foreign.get("errorCode").asText()).isEqualTo(missing.get("errorCode").asText());
        assertThat(foreign.get("message").asText()).isEqualTo(missing.get("message").asText());

        // And the intruder's attempt left the message unread, so nothing was written.
        assertThat(notificationsFor(ownerId).get(0).isRead()).isFalse();
    }

    @Test
    @DisplayName("Section 7.5: no token is refused on every notification route")
    void noTokenIsRefused() throws Exception {
        assertThat(send(HttpMethod.GET, NOTIFICATIONS_URL, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.GET, NOTIFICATIONS_URL + "/1", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> markRead = send(HttpMethod.POST, NOTIFICATIONS_URL + "/1/read", null,
                null);
        assertThat(markRead.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCodeOf(markRead)).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("UC-05 E1: an administrator token cannot reach a student's messages")
    void anAdministratorTokenIsRefused() throws Exception {
        // A notification's title and body are readable prose about one student's spending, and the
        // administrator's announcement (UC-21) writes to this table through a different route under
        // /api/v1/admin/**. So the role rule refuses the read rather than letting a role with no use
        // case for these rows page through them.
        String adminToken = adminLogin();

        ResponseEntity<String> list = send(HttpMethod.GET, NOTIFICATIONS_URL, adminToken, null);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(list)).isEqualTo("ACCESS_DENIED");
        assertThat(send(HttpMethod.GET, NOTIFICATIONS_URL + "/1", adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, NOTIFICATIONS_URL + "/1/read", adminToken, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Section 7.5: a message that does not exist is a 404 on both paths that name it")
    void aMissingMessageIsNotFound() throws Exception {
        String token = loginNewStudent();

        assertThat(send(HttpMethod.GET, NOTIFICATIONS_URL + "/999999999", token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.POST, NOTIFICATIONS_URL + "/999999999/read", token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================================================================
    //  Scope: this module writes none of this
    // ==================================================================

    @Test
    @DisplayName("UC-14: a budget write raises no message, even when the month is already over")
    void budgetWritesRaiseNoMessages() throws Exception {
        // The boundary between UC-13 and UC-14, from the notification side. Setting a limit, changing
        // it downward past what is spent, and removing it all leave the list untouched: the alert
        // belongs to the record that crossed the threshold, and a limit cannot be exceeded at the
        // moment it is set.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        createTransaction(token, categoryId, "50.00", today(), "spent before any limit");
        Long budgetId = createBudget(token, categoryId, "10.00", thisMonth()).get("id").asLong();
        send(HttpMethod.PATCH, BUDGETS_URL + "/" + budgetId, token, Map.of("limitAmount", "5.00"));
        send(HttpMethod.DELETE, BUDGETS_URL + "/" + budgetId, token, null);

        assertThat(notificationCountFor(userId)).isZero();
        assertThat(alertRowsFor(budgetId)).isEmpty();
    }

    @Test
    @DisplayName("UC-14: an outcome already recorded is not recorded again by a second read")
    void readingDoesNotWrite() throws Exception {
        // Reading a list or a single message is a read. The only route that writes is the read
        // marker, so a student who loads the screen repeatedly sees the same unread count rather
        // than clearing it by looking.
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long notificationId = raiseNearAlert(token, userId);

        for (int visit = 0; visit < 3; visit++) {
            send(HttpMethod.GET, NOTIFICATIONS_URL, token, null);
            send(HttpMethod.GET, NOTIFICATIONS_URL + "/" + notificationId, token, null);
        }

        assertThat(notificationsFor(userId).get(0).isRead()).isFalse();
        assertThat(columnInDatabase(notificationId, "notifications", "read_at")).isNull();
    }

    // ==================================================================
    //  Helpers
    // ==================================================================

    /**
     * Sets a limit at 10 and spends 8 against it, which raises exactly one near-threshold alert.
     *
     * <p>The fixture every read-path test needs, expressed once. It returns the new message's id, read
     * from the API rather than the database, so the id under test is the one the client would use.
     */
    private Long raiseNearAlert(String token, Long userId) throws Exception {
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        createBudget(token, categoryId, "10.00", thisMonth());
        createTransaction(token, categoryId, "8.00", today(), "raise the alert");

        List<Long> ids = notificationIds(token);
        assertThat(ids).as("the alert raised exactly one message").hasSize(1);
        assertThat(notificationsFor(userId)).hasSize(1);
        return ids.get(0);
    }

    /** The caller's notification ids in the order the API lists them. */
    private List<Long> notificationIds(String token) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode message : body(send(HttpMethod.GET, NOTIFICATIONS_URL, token, null))) {
            ids.add(message.get("id").asLong());
        }
        return ids;
    }

    /**
     * A timestamp read from either side, as one comparable value.
     *
     * <p>The two sources render the same instant differently and neither is wrong: Jackson writes
     * {@code read_at} as ISO-8601 ({@code 2026-09-25T14:18:12}) because the response field is a
     * {@code LocalDateTime}, while {@link java.sql.Timestamp#toString()} - which the direct database
     * read returns - uses a space and appends the fractional seconds
     * ({@code 2026-09-25 14:18:12.0}). Comparing the strings would fail on formatting rather than on
     * the value, so both are parsed to a {@link LocalDateTime} and compared as instants.
     *
     * <p>The fractional part is dropped rather than compared: MySQL's {@code DATETIME} here has no
     * declared precision, so the column stores whole seconds and the trailing {@code .0} is the
     * JDBC type's rendering rather than a value. A test that compared it would be asserting the
     * driver.
     */
    private static LocalDateTime instantOf(String timestamp) {
        assertThat(timestamp).as("a timestamp must be present").isNotNull();
        return LocalDateTime.parse(timestamp.trim().replace(' ', 'T').replaceAll("\\.\\d+$", ""));
    }
}
