package com.campuscoin.recent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * UC-26 — the transactions a student recently viewed or edited, end to end over HTTP.
 *
 * <p>Everything here runs against a real MySQL with the project's own procedures loaded, because the
 * behaviour under test is not in the Java: the ownership check is inside
 * {@code sp_touch_recent_activity}, the visibility rule is inside {@code v_user_recent_activity}, and
 * the dedupe is {@code uk_recent}. A mocked database would let every one of these pass while the SQL
 * said something else.
 *
 * <p>What this class is trying to break:
 *
 * <ul>
 *   <li>that one student can record activity against another student's transaction, and that the
 *       refusal does not leak which identifiers exist;</li>
 *   <li>that the list can be made to show another student's rows;</li>
 *   <li>that the entry set is right: one row per transaction and action, moved rather than duplicated
 *       when the same action happens twice;</li>
 *   <li>that the description is decrypted on the way out and is an envelope at rest;</li>
 *   <li>that a trashed transaction leaves the list and comes back on restore without being
 *       re-recorded;</li>
 *   <li>that the path is behind the student role rule and not the authenticated catch-all.</li>
 * </ul>
 */
class RecentActivityApiIT extends AbstractRecentActivityApiIT {

    // ==================================================================
    //  Recording
    // ==================================================================

    @Test
    @DisplayName("A view is recorded and comes back as an entry of the caller's own")
    void recordsAView() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long transactionId = aTransaction(token, "Campus cafe - lunch");

        JsonNode entry = recordExpectingCreated(token, transactionId, "VIEWED");

        assertThat(entry.get("transactionId").asLong()).isEqualTo(transactionId);
        assertThat(entry.get("action").asText()).isEqualTo("VIEWED");
        assertThat(entry.get("description").asText()).isEqualTo("Campus cafe - lunch");
        assertThat(activityRowCount(userId, transactionId, "VIEWED")).isEqualTo(1);
    }

    @Test
    @DisplayName("Recording the same view twice moves the entry rather than adding one")
    void reViewingIsIdempotent() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long transactionId = aTransaction(token, "Canteen");

        recordExpectingCreated(token, transactionId, "VIEWED");
        String firstOccurredAt = stringValuesFrom(
                "SELECT occurred_at FROM recent_activity WHERE user_id = ? AND transaction_id = ? "
                        + "AND action = 'VIEWED'", userId, transactionId).get(0);

        // uk_recent is (user_id, transaction_id, action), and the procedure upserts onto it with
        // ON DUPLICATE KEY UPDATE occurred_at = NOW(). So a second view is one row with a later time,
        // not two rows - which is what "recently viewed" has to mean.
        recordExpectingCreated(token, transactionId, "VIEWED");

        assertThat(activityRowCount(userId, transactionId, "VIEWED")).isEqualTo(1);

        JsonNode list = recent(token);
        assertThat(entryTransactionIdsOf(list)).containsExactly(transactionId);
        assertThat(list.get("entries")).hasSize(1);

        String movedOccurredAt = stringValuesFrom(
                "SELECT occurred_at FROM recent_activity WHERE user_id = ? AND transaction_id = ? "
                        + "AND action = 'VIEWED'", userId, transactionId).get(0);
        assertThat(movedOccurredAt)
                .as("the second view should have moved the entry's time, not left it")
                .isGreaterThanOrEqualTo(firstOccurredAt);
    }

    @Test
    @DisplayName("Viewing and editing one transaction are two entries, because the action is part of the key")
    void viewedAndEditedAreSeparateEntries() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long transactionId = aTransaction(token, "Boba");

        recordExpectingCreated(token, transactionId, "VIEWED");
        recordExpectingCreated(token, transactionId, "EDITED");

        // uk_recent is (user_id, transaction_id, action), so the two acts are two rows. A list that
        // collapsed them would lose the distinction UC-26 is about. The order is the query's, not the
        // fixture's: the two writes land inside the same second, so the tie-break decides.
        assertThat(actionsFor(userId, transactionId)).containsExactlyInAnyOrder("EDITED", "VIEWED");

        JsonNode list = recent(token);
        assertThat(list.get("entries")).hasSize(2);
        assertThat(entryActionsOf(list)).containsExactlyInAnyOrder("VIEWED", "EDITED");
    }

    @Test
    @DisplayName("The recorded entry carries the category, amount, date and decrypted description")
    void entryCarriesTheTransactionDetail() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aTransaction(token, "Lunch with Linh");

        JsonNode entry = recordExpectingCreated(token, transactionId, "VIEWED");

        assertThat(entry.get("categoryId").asLong()).isEqualTo(defaultCategoryId(FOOD));
        assertThat(entry.get("categoryType").asText()).isEqualTo("EXPENSE");
        // decimalValue() rather than asText(): Jackson parses a JSON float as a double unless
        // USE_BIG_DECIMAL_FOR_FLOATS is on, so asText() reports 25.0 for a stored 25.00 and would make
        // this assertion about scale rather than about the figure. The suite's other amount
        // assertions compare the same way - see TransactionApiIT and RecurringRuleApiIT.
        assertThat(entry.get("amount").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(entry.get("txnDate").asText()).isEqualTo(today().toString());
        assertThat(entry.get("description").asText()).isEqualTo("Lunch with Linh");
        assertThat(entry.hasNonNull("occurredAt")).isTrue();
    }

    @Test
    @DisplayName("The description is an envelope at rest and plaintext in the response")
    void descriptionIsEncryptedAtRest() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aTransaction(token, "Secret lunch");

        JsonNode entry = recordExpectingCreated(token, transactionId, "VIEWED");

        // Read straight from the column: asserting through the response would prove only that the round
        // trip works, not that the database never held the words.
        String stored = storedDescriptionOf(transactionId);
        assertThat(stored).as("stored description").isNotEqualTo("Secret lunch");
        assertThat(stored).doesNotContain("Secret lunch");
        assertThat(decryptField(stored)).isEqualTo("Secret lunch");

        assertThat(entry.get("description").asText()).isEqualTo("Secret lunch");
    }

    @Test
    @DisplayName("A transaction with no description records an entry that omits the field")
    void noDescriptionIsOmitted() throws Exception {
        String token = loginNewStudent();
        Long transactionId = createTransaction(token, defaultCategoryId(FOOD), "9.00", today(), null);

        JsonNode entry = recordExpectingCreated(token, transactionId, "VIEWED");

        assertThat(entry.has("description")).isFalse();
    }

    // ==================================================================
    //  The list
    // ==================================================================

    @Test
    @DisplayName("The list is the caller's own entries, most recent first")
    void listsMostRecentFirst() throws Exception {
        String token = loginNewStudent();
        Long first = aTransaction(token, "First");
        Long second = aTransaction(token, "Second");

        recordExpectingCreated(token, first, "VIEWED");
        recordExpectingCreated(token, second, "VIEWED");

        JsonNode list = recent(token);

        assertThat(list.get("limit").asInt()).isEqualTo(10);
        assertThat(entryTransactionIdsOf(list)).containsExactlyInAnyOrder(first, second);
        assertThat(list.get("entries")).hasSize(2);

        // Ordered by occurred_at DESC. The two writes are inside the same second in a test run, so the
        // id tie-break decides; what matters is that the order is stable between two identical calls.
        assertThat(entryTransactionIdsOf(recent(token)))
                .containsExactlyElementsOf(entryTransactionIdsOf(list));
    }

    @Test
    @DisplayName("A student who has viewed nothing gets an empty list, not a 404")
    void emptyListIsARealAnswer() throws Exception {
        String token = loginNewStudent();

        JsonNode list = recent(token);

        assertThat(list.get("entries")).isEmpty();
        assertThat(list.get("limit").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("The list holds only the caller's entries")
    void listIsScopedToTheCaller() throws Exception {
        String tokenA = loginNewStudent();
        String tokenB = loginNewStudent();

        Long aTransaction = aTransaction(tokenA, "A's lunch");
        Long bTransaction = aTransaction(tokenB, "B's books");

        recordExpectingCreated(tokenA, aTransaction, "VIEWED");
        recordExpectingCreated(tokenB, bTransaction, "VIEWED");

        assertThat(entryTransactionIdsOf(recent(tokenA))).containsExactly(aTransaction);
        assertThat(entryTransactionIdsOf(recent(tokenB))).containsExactly(bTransaction);
    }

    @Test
    @DisplayName("limit is honoured, reported back, and refused when out of range")
    void limitIsHonouredAndBounded() throws Exception {
        String token = loginNewStudent();
        Long first = aTransaction(token, "One");
        Long second = aTransaction(token, "Two");

        recordExpectingCreated(token, first, "VIEWED");
        recordExpectingCreated(token, second, "VIEWED");

        JsonNode capped = body(recentWithLimit(token, "1"));
        assertThat(capped.get("limit").asInt()).isEqualTo(1);
        assertThat(capped.get("entries")).hasSize(1);

        // Zero and above the ceiling are refused rather than clamped: the response reports the limit it
        // applied, so silently changing it would make its own body untrue.
        for (String bad : List.of("0", "-1", "51")) {
            ResponseEntity<String> response = recentWithLimit(token, bad);
            assertThat(response.getStatusCode())
                    .as("limit=%s body=%s", bad, response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
            assertThat(fieldNamesIn(body(response))).containsExactly("limit");
        }
    }

    @Test
    @DisplayName("The entry set is exactly the documented fields, and carries no owner")
    void documentedFieldsOnly() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aTransaction(token, "Lunch");

        JsonNode entry = recordExpectingCreated(token, transactionId, "VIEWED");

        assertThat(fieldNamesOf(entry)).containsExactlyElementsOf(DOCUMENTED_ENTRY_FIELDS);
        assertThat(fieldNamesOf(recent(token))).containsExactlyElementsOf(DOCUMENTED_LIST_FIELDS);
        assertThat(entry.has("userId")).isFalse();
    }

    // ==================================================================
    //  Ownership: BR-02
    // ==================================================================

    @Test
    @DisplayName("Another student's transaction cannot be recorded, and looks exactly like a missing one")
    void cannotRecordAgainstAnotherStudentsTransaction() throws Exception {
        String tokenA = loginNewStudent();
        String tokenB = loginNewStudent();
        Long bTransaction = aTransaction(tokenB, "B's books");

        // The row exists and belongs to somebody else. The procedure refuses it with 45000, and the
        // response must be indistinguishable from the one for an identifier that matches nothing - or
        // a caller could enumerate other students' transaction identifiers one request at a time.
        ResponseEntity<String> notMine = record(tokenA, bTransaction, "VIEWED");
        ResponseEntity<String> notThere = record(tokenA, 999_999_999L, "VIEWED");

        assertThat(notMine.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notThere.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCodeOf(notMine)).isEqualTo("NOT_FOUND");
        assertThat(errorCodeOf(notThere)).isEqualTo("NOT_FOUND");
        assertThat(body(notMine).get("message").asText())
                .isEqualTo(body(notThere).get("message").asText());

        // And nothing was written on the other student's behalf.
        Long bUserId = userIdOf(tokenB);
        assertThat(activityRowCount(bUserId, bTransaction, "VIEWED")).isZero();
        assertThat(recent(tokenA).get("entries")).isEmpty();
    }

    // ==================================================================
    //  The trash, and the timestamp the client does not send
    // ==================================================================

    @Test
    @DisplayName("A trashed transaction leaves the list, and restoring it brings the entry back")
    void trashHidesAndRestoreReveals() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long transactionId = aTransaction(token, "Regrettable purchase");

        recordExpectingCreated(token, transactionId, "VIEWED");
        assertThat(entryTransactionIdsOf(recent(token))).containsExactly(transactionId);

        assertThat(trash(token, transactionId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(recent(token).get("entries")).isEmpty();

        // The activity row survives the soft delete; the view is what hides it. That is why restoring
        // needs no repair step and why the entry is not re-recorded.
        assertThat(activityRowCount(userId, transactionId, "VIEWED")).isEqualTo(1);

        assertThat(restore(token, transactionId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entryTransactionIdsOf(recent(token))).containsExactly(transactionId);
        assertThat(activityRowCount(userId, transactionId, "VIEWED")).isEqualTo(1);
    }

    @Test
    @DisplayName("occurredAt comes from the server, and a client cannot set it")
    void occurredAtIsServerSide() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aTransaction(token, "Lunch");

        // Sent and ignored rather than refused: the field is not part of the contract, and rejecting it
        // would be a new way for a client to fail. What matters is that the stored time is the
        // database's, not the future one sent here.
        ResponseEntity<String> response = send(HttpMethod.POST, RECENT_URL, token, Map.of(
                "transactionId", transactionId,
                "action", "VIEWED",
                "occurredAt", "2099-01-01T00:00:00"));

        assertThat(response.getStatusCode())
                .as("record body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(body(response).get("occurredAt").asText()).doesNotStartWith("2099");

        Long userId = userIdOf(token);
        String stored = stringValuesFrom(
                "SELECT occurred_at FROM recent_activity WHERE user_id = ? AND transaction_id = ?",
                userId, transactionId).get(0);
        assertThat(stored).startsWith(String.valueOf(LocalDate.now(APPLICATION_ZONE).getYear()));
    }

    // ==================================================================
    //  Validation
    // ==================================================================

    @Test
    @DisplayName("A missing transactionId, a missing action, or an unknown action are field errors")
    void rejectsIncompleteRequests() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> noId = send(HttpMethod.POST, RECENT_URL, token,
                Map.of("action", "VIEWED"));
        assertThat(noId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(noId)).isEqualTo("VALIDATION_ERROR");
        assertThat(fieldNamesIn(body(noId))).containsExactly("transactionId");

        ResponseEntity<String> noAction = send(HttpMethod.POST, RECENT_URL, token,
                Map.of("transactionId", 1));
        assertThat(noAction.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(noAction)).isEqualTo("VALIDATION_ERROR");
        assertThat(fieldNamesIn(body(noAction))).containsExactly("action");

        // A third action never reaches the procedure: `fail-on-numbers-for-enums` and Jackson's enum
        // binding reject it at the boundary, which is what keeps the procedure's own 45000 meaning
        // "not your transaction" rather than "bad action".
        ResponseEntity<String> badAction = record(token, 1L, "DELETED");
        assertThat(badAction.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldNamesIn(body(badAction))).containsExactly("action");

        // An ordinal is not a member name, and must not be read as one.
        ResponseEntity<String> ordinal = send(HttpMethod.POST, RECENT_URL, token,
                Map.of("transactionId", 1, "action", 0));
        assertThat(ordinal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ==================================================================
    //  Security (section 7.5)
    // ==================================================================

    @Test
    @DisplayName("Section 7.5: no token is 401, and an administrator token is 403 on both endpoints")
    void roleAndTokenAreEnforced() throws Exception {
        String studentToken = loginNewStudent();
        Long transactionId = aTransaction(studentToken, "Lunch");
        String adminToken = adminLogin();
        Map<String, Object> body = Map.of("transactionId", transactionId, "action", "VIEWED");

        assertThat(send(HttpMethod.GET, RECENT_URL, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.POST, RECENT_URL, null, body).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // The path matches none of the student prefixes, so without its own rule it would fall to
        // /api/** — which admits any authenticated account. This is the assertion that fails if that
        // rule is ever dropped, and it is why the rule exists.
        assertThat(errorCodeOf(send(HttpMethod.GET, RECENT_URL, adminToken, null)))
                .isEqualTo("ACCESS_DENIED");
        assertThat(send(HttpMethod.GET, RECENT_URL, adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(send(HttpMethod.POST, RECENT_URL, adminToken, body)))
                .isEqualTo("ACCESS_DENIED");
    }
}
