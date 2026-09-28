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

class NotificationApiIT extends AbstractBudgetApiIT {

    @Test
    @DisplayName("UC-14/BR-12: a record crossing the near threshold raises one alert and one message")
    void crossingTheNearThresholdRaisesOneAlert() throws Exception {

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

        assertThat(row.linkUrl()).isEqualTo("/budgets");
        assertThat(row.refEntityType()).isEqualTo("BUDGET");
        assertThat(row.refEntityId()).isEqualTo(budgetId);

        assertThat(row.isRead()).isFalse();
        assertThat(row.readAt()).isNull();
    }

    @Test
    @DisplayName("UC-14/BR-12: passing the limit raises a second alert, and staying past it raises "
            + "no third")
    void crossingTheExceededThresholdRaisesOneAlert() throws Exception {

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

        createTransaction(token, categoryId, "1.00", today(), "still over");
        createTransaction(token, categoryId, "3.00", today(), "further over");

        assertThat(alertRowsFor(budgetId)).containsExactly("NEAR|80.00", "EXCEEDED|110.00");
        assertThat(notificationCountFor(userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("UC-14/BR-09: moving a record to the trash does not withdraw the warning")
    void deletingTheRecordDoesNotWithdrawTheAlert() throws Exception {

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

        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);

        createTransaction(token, categoryId, "500.00", today(), "no limit set");

        assertThat(notificationCountFor(userId)).isZero();
    }

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

        assertThat(fieldNamesOf(message)).doesNotContain("readAt");
        assertThat(message.get("isRead").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("UC-14: the list is newest first, so the latest warning is the first row")
    void theListIsNewestFirst() throws Exception {

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

        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long notificationId = raiseNearAlert(token, userId);

        send(HttpMethod.GET, NOTIFICATIONS_URL + "/" + notificationId, token, null);
        send(HttpMethod.GET, NOTIFICATIONS_URL + "/" + notificationId, token, null);

        assertThat(notificationsFor(userId).get(0).isRead()).isFalse();
        assertThat(body(send(HttpMethod.GET, NOTIFICATIONS_URL + "/" + notificationId, token, null))
                .get("isRead").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("UC-14 B4: marking read sets the read flag and the timestamp together")
    void markingReadSetsFlagAndTimestampTogether() throws Exception {

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

        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long notificationId = raiseNearAlert(token, userId);
        String readUrl = NOTIFICATIONS_URL + "/" + notificationId + "/read";

        String firstReadAt = body(send(HttpMethod.POST, readUrl, token, null)).get("readAt").asText();

        ResponseEntity<String> second = send(HttpMethod.POST, readUrl, token, null);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(second).get("isRead").asBoolean()).isTrue();
        assertThat(instantOf(body(second).get("readAt").asText())).isEqualTo(instantOf(firstReadAt));
        assertThat(instantOf(notificationsFor(userId).get(0).readAt()))
                .isEqualTo(instantOf(firstReadAt));

        assertThat(booleanInDatabase(notificationId, "notifications", "is_read")).isTrue();
    }

    @Test
    @DisplayName("UC-14: marking one message read leaves the others unread")
    void markingOneReadLeavesTheOthersUnread() throws Exception {

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

        assertThat(notificationsFor(userId).get(0).isRead()).isTrue();
    }

    @Test
    @DisplayName("UC-14: a student cannot see a message addressed to someone else")
    void theListShowsOnlyTheCallersMessages() throws Exception {

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

        JsonNode foreign = body(send(HttpMethod.GET, NOTIFICATIONS_URL + "/" + notificationId,
                intruder, null));
        JsonNode missing = body(send(HttpMethod.GET, NOTIFICATIONS_URL + "/999999999", intruder,
                null));
        assertThat(foreign.get("errorCode").asText()).isEqualTo(missing.get("errorCode").asText());
        assertThat(foreign.get("message").asText()).isEqualTo(missing.get("message").asText());

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

    @Test
    @DisplayName("UC-14: a budget write raises no message, even when the month is already over")
    void budgetWritesRaiseNoMessages() throws Exception {

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

    private Long raiseNearAlert(String token, Long userId) throws Exception {
        Long categoryId = defaultCategoryId(DEFAULT_EXPENSE_NAME);
        createBudget(token, categoryId, "10.00", thisMonth());
        createTransaction(token, categoryId, "8.00", today(), "raise the alert");

        List<Long> ids = notificationIds(token);
        assertThat(ids).as("the alert raised exactly one message").hasSize(1);
        assertThat(notificationsFor(userId)).hasSize(1);
        return ids.get(0);
    }

    private List<Long> notificationIds(String token) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode message : body(send(HttpMethod.GET, NOTIFICATIONS_URL, token, null))) {
            ids.add(message.get("id").asLong());
        }
        return ids;
    }

    private static LocalDateTime instantOf(String timestamp) {
        assertThat(timestamp).as("a timestamp must be present").isNotNull();
        return LocalDateTime.parse(timestamp.trim().replace(' ', 'T').replaceAll("\\.\\d+$", ""));
    }
}
