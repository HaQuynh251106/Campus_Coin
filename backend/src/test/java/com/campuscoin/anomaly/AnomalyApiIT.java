package com.campuscoin.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

class AnomalyApiIT extends AbstractAnomalyApiIT {

    @Test
    @DisplayName("UC-24: two records of the same amount a day apart are found, and the mark clears")
    void aDuplicateIsFoundAndCleared() throws Exception {
        String token = loginNewStudent();

        Long first = aRecordDaysAgo(token, "25.00", 1, "Coffee");
        Long second = aRecord(token, "25.00", "Coffee");

        JsonNode scan = scanExpectingOk(token);

        assertThat(scan.get("examined").asInt()).isEqualTo(2);
        assertThat(scan.get("flagged").asInt()).isEqualTo(1);
        assertThat(scan.get("cleared").asInt()).isZero();
        assertThatCountsAreConsistent(scan);

        StoredFlag stored = storedFlagOf(second);
        assertThat(stored.isFlagged()).isTrue();
        assertThat(stored.flagType()).isEqualTo("DUPLICATE");
        assertThat(stored.flagNote()).contains(today().minusDays(1).toString());
        assertThat(storedFlagOf(first).isFlagged()).isFalse();

        JsonNode flagged = flagged(token);
        assertThat(entryTransactionIdsOf(flagged)).containsExactly(second);
        JsonNode entry = entryFor(flagged, second);
        assertThat(entry.get("flagType").asText()).isEqualTo("DUPLICATE");
        assertThat(entry.get("isFlagged").asBoolean()).isTrue();
        assertThat(entry.get("description").asText()).isEqualTo("Coffee");
        assertThat(entry.get("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("25.00"));

        trash(token, second);

        JsonNode rescan = scanExpectingOk(token);
        assertThat(rescan.get("examined").asInt()).isEqualTo(1);
        assertThat(rescan.get("flagged").asInt()).isZero();
        assertThat(rescan.get("cleared").asInt()).isZero();
        assertThat(rescan.get("unchanged").asInt()).isEqualTo(1);
        assertThat(rescan.get("entries")).isEmpty();

        assertThat(flagged(token).get("entries")).isEmpty();
    }

    @Test
    @DisplayName("UC-24: trashing one of a pair clears the other, with no separate step")
    void trashingOneOfAPairClearsTheOther() throws Exception {

        String token = loginNewStudent();

        Long first = aRecordDaysAgo(token, "40.00", 1, null);
        Long second = aRecord(token, "40.00", null);

        scanExpectingOk(token);
        assertThat(storedFlagOf(second).flagType()).isEqualTo("DUPLICATE");

        trash(token, first);

        JsonNode rescan = scanExpectingOk(token);
        assertThat(rescan.get("cleared").asInt()).isEqualTo(1);
        assertThat(rescan.get("flagged").asInt()).isZero();
        assertThat(rescan.get("examined").asInt()).isEqualTo(1);
        assertThatCountsAreConsistent(rescan);

        assertThat(storedFlagOf(second).isFlagged()).isFalse();
        assertThat(storedFlagOf(second).flagType()).isEqualTo("NONE");
        assertThat(storedFlagOf(second).flagNote()).isNull();
    }

    @Test
    @DisplayName("UC-24: a large amount among ordinary ones is found, and correcting it clears the mark")
    void anUnusualAmountIsFoundAndCleared() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(FOOD);

        createTransaction(token, categoryId, "10.00", today().minusDays(30), "Lunch");
        createTransaction(token, categoryId, "10.00", today().minusDays(20), "Lunch");
        createTransaction(token, categoryId, "10.00", today().minusDays(10), "Lunch");
        Long large = createTransaction(token, categoryId, "90.00", today(), "Textbooks");

        JsonNode scan = scanExpectingOk(token);

        assertThat(scan.get("examined").asInt()).isEqualTo(4);
        assertThat(scan.get("flagged").asInt()).isEqualTo(1);
        assertThatCountsAreConsistent(scan);

        StoredFlag stored = storedFlagOf(large);
        assertThat(stored.isFlagged()).isTrue();
        assertThat(stored.flagType()).isEqualTo("UNUSUAL_AMOUNT");

        assertThat(stored.flagNote()).contains("90.00").contains("10.00");

        Map<String, Object> correction = new LinkedHashMap<>();
        correction.put("amount", "10.00");
        assertThat(patchTransaction(token, large, correction).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode rescan = scanExpectingOk(token);
        assertThat(rescan.get("cleared").asInt()).isEqualTo(1);
        assertThat(rescan.get("flagged").asInt()).isZero();
        assertThatCountsAreConsistent(rescan);

        assertThat(storedFlagOf(large).isFlagged()).isFalse();
        assertThat(flagged(token).get("entries")).isEmpty();
    }

    @Test
    @DisplayName("UC-24: correcting the amount of one of a pair clears the mark on the other")
    void correctingOneOfAPairClearsTheOther() throws Exception {

        String token = loginNewStudent();

        Long first = aRecordDaysAgo(token, "25.00", 1, null);
        Long second = aRecord(token, "25.00", null);

        scanExpectingOk(token);
        assertThat(storedFlagOf(second).isFlagged()).isTrue();

        Map<String, Object> correction = new LinkedHashMap<>();
        correction.put("amount", "12.00");
        assertThat(patchTransaction(token, first, correction).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode rescan = scanExpectingOk(token);
        assertThat(rescan.get("cleared").asInt()).isEqualTo(1);
        assertThat(rescan.get("flagged").asInt()).isZero();

        assertThat(storedFlagOf(second).isFlagged()).isFalse();
        assertThat(flagged(token).get("entries")).isEmpty();
    }

    @Test
    @DisplayName("UC-24: the same price a month apart is not a duplicate")
    void theSamePriceAMonthApartIsNotADuplicate() throws Exception {

        String token = loginNewStudent();

        Long first = aRecordDaysAgo(token, "50.00", 40, null);
        Long second = aRecord(token, "50.00", null);

        JsonNode scan = scanExpectingOk(token);

        assertThat(scan.get("flagged").asInt()).isZero();
        assertThat(scan.get("examined").asInt()).isEqualTo(2);
        assertThatCountsAreConsistent(scan);
        assertThat(storedFlagOf(first).isFlagged()).isFalse();
        assertThat(storedFlagOf(second).isFlagged()).isFalse();
        assertThat(flagged(token).get("entries")).isEmpty();
    }

    @Test
    @DisplayName("UC-24: a record at exactly three times the student's average is unusual")
    void aRecordAtExactlyTheMultipleIsUnusual() throws Exception {

        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(FOOD);

        createTransaction(token, categoryId, "10.00", today().minusDays(30), null);
        createTransaction(token, categoryId, "10.00", today().minusDays(20), null);
        Long boundary = createTransaction(token, categoryId, "30.00", today().minusDays(10), null);
        createTransaction(token, categoryId, "10.00", today(), null);

        JsonNode scan = scanExpectingOk(token);

        assertThat(scan.get("examined").asInt()).isEqualTo(4);
        assertThat(scan.get("flagged").asInt()).isEqualTo(1);
        assertThatCountsAreConsistent(scan);
        assertThat(storedFlagOf(boundary).flagType()).isEqualTo("UNUSUAL_AMOUNT");
    }

    @Test
    @DisplayName("UC-24: a record just under three times the student's average is not unusual")
    void aRecordJustUnderTheMultipleIsNotUnusual() throws Exception {

        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(FOOD);

        createTransaction(token, categoryId, "10.00", today().minusDays(30), null);
        createTransaction(token, categoryId, "10.00", today().minusDays(20), null);
        Long under = createTransaction(token, categoryId, "29.99", today().minusDays(10), null);
        createTransaction(token, categoryId, "10.00", today(), null);

        JsonNode scan = scanExpectingOk(token);

        assertThat(scan.get("examined").asInt()).isEqualTo(4);
        assertThat(scan.get("flagged").asInt()).isZero();
        assertThatCountsAreConsistent(scan);
        assertThat(storedFlagOf(under).isFlagged()).isFalse();
    }

    @Test
    @DisplayName("UC-24: a baseline is built from the student's records, not from the new one")
    void theNewRecordDoesNotRaiseItsOwnBaseline() throws Exception {

        String token = loginNewStudent();

        Long ordinary = aRecordDaysAgo(token, "10.00", 40, null);
        Long large = aRecord(token, "100.00", null);

        scanExpectingOk(token);

        assertThat(storedFlagOf(large).flagType()).isEqualTo("UNUSUAL_AMOUNT");
        assertThat(storedFlagOf(ordinary).isFlagged()).isFalse();
    }

    @Test
    @DisplayName("UC-24: the same amount and category within the window is a duplicate before it is unusual")
    void aDuplicateWinsOverUnusuallyLarge() throws Exception {

        String token = loginNewStudent();

        Long first = aRecordDaysAgo(token, "900.00", 1, null);
        Long second = aRecord(token, "900.00", null);

        scanExpectingOk(token);

        StoredFlag stored = storedFlagOf(second);
        assertThat(stored.flagType()).isEqualTo("DUPLICATE");
        assertThat(stored.flagNote()).contains("already entered");
        assertThat(storedFlagOf(first).isFlagged()).isFalse();
    }

    @Test
    @DisplayName("UC-24: a second scan over unchanged records writes nothing at all")
    void aSecondScanWritesNothing() throws Exception {

        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(FOOD);

        Long first = createTransaction(token, categoryId, "10.00", today().minusDays(20), null);
        Long second = createTransaction(token, categoryId, "10.00", today().minusDays(10), null);
        Long third = createTransaction(token, categoryId, "90.00", today(), null);
        Long fourth = createTransaction(token, categoryId, "50.00", today().minusDays(1), null);

        scanExpectingOk(token);

        Map<Long, Integer> historyBefore = new LinkedHashMap<>();
        for (Long id : List.of(first, second, third, fourth)) {
            historyBefore.put(id, historyCountOf(id));
        }

        JsonNode rescan = scanExpectingOk(token);

        assertThat(rescan.get("flagged").asInt()).isZero();
        assertThat(rescan.get("cleared").asInt()).isZero();
        assertThat(rescan.get("unchanged").asInt()).isEqualTo(4);
        assertThat(rescan.get("examined").asInt()).isEqualTo(4);
        assertThatCountsAreConsistent(rescan);

        for (Long id : List.of(first, second, third, fourth)) {
            assertThat(historyCountOf(id))
                    .as("history rows for transaction %d", id)
                    .isEqualTo(historyBefore.get(id));
        }
    }

    @Test
    @DisplayName("UC-24: a scan that changes a mark leaves a history row for it (BR-09)")
    void aScanThatChangesAMarkLeavesAHistoryRow() throws Exception {

        String token = loginNewStudent();

        Long first = aRecordDaysAgo(token, "25.00", 1, null);
        Long second = aRecord(token, "25.00", null);

        int historyBefore = historyCountOf(second);

        scanExpectingOk(token);

        assertThat(historyCountOf(second)).isEqualTo(historyBefore + 1);
        assertThat(stringValuesFrom("SELECT changed_fields FROM transaction_history "
                + "WHERE transaction_id = ? ORDER BY id DESC LIMIT 1", second))
                .singleElement()
                .asString()
                .contains("flagType");
        assertThat(historyCountOf(first)).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-24: the check leaves records the student never touched alone")
    void recordsThatAreFineAreNeverWrittenTo() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(FOOD);
        Long one = createTransaction(token, categoryId, "10.00", today().minusDays(20), null);
        Long two = createTransaction(token, categoryId, "10.00", today().minusDays(10), null);

        scanExpectingOk(token);

        assertThat(historyCountOf(one)).isEqualTo(1);
        assertThat(historyCountOf(two)).isEqualTo(1);
        assertThat(storedFlagOf(one).flagType()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("UC-24: a student with nothing flagged gets an empty list, not a 404")
    void nothingFlaggedIsAnEmptyList() throws Exception {

        String token = loginNewStudent();

        ResponseEntity<String> response = send(HttpMethod.GET, ANOMALIES_URL, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = body(response);
        assertThat(body.get("entries")).isEmpty();
        assertThat(body.get("limit").asInt()).isEqualTo(20);
        assertThat(fieldNamesOf(body)).containsExactlyElementsOf(DOCUMENTED_LIST_FIELDS);
    }

    @Test
    @DisplayName("UC-24: reading the list does not run the check")
    void readingTheListDoesNotRunTheCheck() throws Exception {

        String token = loginNewStudent();

        Long first = aRecordDaysAgo(token, "25.00", 1, null);
        Long second = aRecord(token, "25.00", null);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(flagged(token).get("entries")).isEmpty();
        }

        assertThat(storedFlagOf(second).flagType()).isEqualTo("NONE");
        assertThat(historyCountOf(second)).isEqualTo(1);

        scanExpectingOk(token);

        assertThat(flagged(token).get("entries")).hasSize(1);
        assertThat(storedFlagOf(first).flagType()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("UC-24: the list carries the documented fields and no owner")
    void theEntryCarriesExactlyTheDocumentedFields() throws Exception {
        String token = loginNewStudent();
        aRecordDaysAgo(token, "25.00", 1, "Coffee");
        aRecord(token, "25.00", "Coffee");
        scanExpectingOk(token);

        JsonNode entry = flagged(token).get("entries").get(0);

        assertThat(fieldNamesOf(entry)).containsExactlyElementsOf(DOCUMENTED_ENTRY_FIELDS);
    }

    @Test
    @DisplayName("UC-24: a record with no description omits the field rather than carrying a null")
    void aRecordWithNoDescriptionOmitsTheField() throws Exception {

        String token = loginNewStudent();
        aRecordDaysAgo(token, "25.00", 1, null);
        aRecord(token, "25.00", null);
        scanExpectingOk(token);

        JsonNode entry = entryFor(flagged(token), entryTransactionIdsOf(flagged(token)).get(0));

        assertThat(entry.has("description")).isFalse();
        assertThat(entry.has("flagNote")).isTrue();
    }

    @Test
    @DisplayName("UC-24: the stored description is an envelope, and the response holds the words")
    void theStoredDescriptionIsEncryptedAtRest() throws Exception {

        String token = loginNewStudent();
        aRecordDaysAgo(token, "25.00", 1, "Lunch with the study group");
        Long second = aRecord(token, "25.00", "Lunch with the study group");

        scanExpectingOk(token);

        String stored = storedDescriptionOf(second);
        assertThat(stored).isNotEqualTo("Lunch with the study group");
        assertThat(stored).doesNotContain("Lunch with the study group");
        assertThat(decryptField(stored)).isEqualTo("Lunch with the study group");

        assertThat(entryFor(flagged(token), second).get("description").asText())
                .isEqualTo("Lunch with the study group");
    }

    @Test
    @DisplayName("UC-24: the response is ordered most recent first")
    void theListIsOrderedMostRecentFirst() throws Exception {
        String token = loginNewStudent();
        Long food = defaultCategoryId(FOOD);
        Long transport = defaultCategoryId("Transport");

        createTransaction(token, food, "10.00", today().minusDays(60), null);
        createTransaction(token, food, "10.00", today().minusDays(40), null);
        Long foodBaseline = createTransaction(token, food, "10.00", today().minusDays(20), null);
        Long unusual = createTransaction(token, food, "900.00", today().minusDays(2), null);

        createTransaction(token, transport, "50.00", today().minusDays(1), null);
        Long duplicate = createTransaction(token, transport, "50.00", today(), null);

        scanExpectingOk(token);

        assertThat(entryTransactionIdsOf(flagged(token))).containsExactly(duplicate, unusual);
        assertThat(storedFlagOf(duplicate).flagType()).isEqualTo("DUPLICATE");
        assertThat(storedFlagOf(unusual).flagType()).isEqualTo("UNUSUAL_AMOUNT");
        assertThat(storedFlagOf(foodBaseline).isFlagged()).isFalse();
    }

    @Test
    @DisplayName("UC-24: the flagged list is bounded, and an out-of-range limit is refused")
    void theLimitIsBoundedAndRefusedRatherThanClamped() throws Exception {

        String token = loginNewStudent();
        Long food = defaultCategoryId(FOOD);
        Long transport = defaultCategoryId("Transport");

        createTransaction(token, food, "10.00", today().minusDays(60), null);
        createTransaction(token, food, "10.00", today().minusDays(40), null);
        createTransaction(token, food, "10.00", today().minusDays(20), null);
        Long olderUnusual = createTransaction(token, food, "900.00", today().minusDays(9), null);
        Long newerUnusual = createTransaction(token, food, "700.00", today().minusDays(5), null);

        createTransaction(token, transport, "50.00", today().minusDays(2), null);
        Long duplicate = createTransaction(token, transport, "50.00", today().minusDays(1), null);

        scanExpectingOk(token);

        assertThat(entryTransactionIdsOf(flagged(token)))
                .containsExactly(duplicate, newerUnusual, olderUnusual);

        ResponseEntity<String> limited = send(HttpMethod.GET, ANOMALIES_URL + "?limit=2", token, null);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = body(limited);
        assertThat(body.get("limit").asInt()).isEqualTo(2);
        assertThat(entryTransactionIdsOf(body)).containsExactly(duplicate, newerUnusual);

        for (String refused : List.of("0", "-1", "101", "abc")) {
            ResponseEntity<String> response = flaggedWithLimit(token, refused);
            assertThat(response.getStatusCode())
                    .as("limit=%s body=%s", refused, response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        assertThat(fieldNamesIn(body(flaggedWithLimit(token, "101")))).containsExactly("limit");
    }

    @Test
    @DisplayName("UC-24: one student's records are invisible to another, and the check never sees them")
    void anotherStudentsRecordsAreInvisible() throws Exception {
        String firstToken = loginNewStudent();
        String secondToken = loginNewStudent();

        Long mine = aRecord(firstToken, "25.00", "Mine");
        Long theirs = aRecord(secondToken, "25.00", "Theirs");

        JsonNode firstStudentScan = scanExpectingOk(firstToken);
        assertThat(firstStudentScan.get("examined").asInt()).isEqualTo(1);
        assertThat(firstStudentScan.get("flagged").asInt()).isZero();
        assertThat(flagged(firstToken).get("entries")).isEmpty();

        JsonNode secondStudentScan = scanExpectingOk(secondToken);
        assertThat(secondStudentScan.get("examined").asInt()).isEqualTo(1);
        assertThat(secondStudentScan.get("flagged").asInt()).isZero();
        assertThat(flagged(secondToken).get("entries")).isEmpty();

        assertThat(storedFlagOf(mine).isFlagged()).isFalse();
        assertThat(storedFlagOf(theirs).isFlagged()).isFalse();
        assertThat(entryTransactionIdsOf(flagged(firstToken))).doesNotContain(theirs);
    }

    @Test
    @DisplayName("UC-24: the seeded student's own records are examined, and the check leaves them alone")
    void theSeededStudentsRecordsAreExamined() throws Exception {

        String token = seededStudentLogin();
        Long userId = userIdOf(token);

        int liveRecords = countOf("SELECT COUNT(*) FROM transactions "
                + "WHERE user_id = ? AND is_deleted = 0", userId);

        JsonNode scan = scanExpectingOk(token);

        assertThat(scan.get("examined").asInt()).isEqualTo(liveRecords);
        assertThatCountsAreConsistent(scan);
        assertThat(scan.get("flagged").asInt() + scan.get("cleared").asInt())
                .isLessThanOrEqualTo(liveRecords);
    }

    @Test
    @DisplayName("UC-24: neither endpoint is reachable without a token")
    void neitherEndpointIsReachableAnonymously() {
        assertThat(send(HttpMethod.GET, ANOMALIES_URL, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.POST, SCAN_URL, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UC-24: an administrator's token is refused, because the check writes")
    void anAdministratorIsRefused() throws Exception {

        String adminToken = adminLogin();

        assertThat(send(HttpMethod.GET, ANOMALIES_URL, adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, SCAN_URL, adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("UC-24: the scan reads the caller, not a body, so there is nothing to forge")
    void theScanTakesNoBodyThatCouldNameAnotherStudent() throws Exception {

        String token = loginNewStudent();
        Long mine = aRecordDaysAgo(token, "25.00", 1, null);
        Long second = aRecord(token, "25.00", null);

        ResponseEntity<String> response = send(HttpMethod.POST, SCAN_URL, token,
                Map.of("userId", 1, "flagType", "NONE", "transactionId", mine));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response).get("flagged").asInt()).isEqualTo(1);
        assertThat(storedFlagOf(second).flagType()).isEqualTo("DUPLICATE");
    }

    @Test
    @DisplayName("UC-24: a student with no records gets a scan of nothing rather than an error")
    void aStudentWithNoRecordsIsScannedWithoutError() throws Exception {
        String token = loginNewStudent();

        JsonNode scan = scanExpectingOk(token);

        assertThat(scan.get("examined").asInt()).isZero();
        assertThat(scan.get("flagged").asInt()).isZero();
        assertThat(scan.get("cleared").asInt()).isZero();
        assertThat(scan.get("unchanged").asInt()).isZero();
        assertThat(scan.get("entries")).isEmpty();
        assertThatCountsAreConsistent(scan);
        assertThat(fieldNamesOf(scan)).containsExactlyElementsOf(DOCUMENTED_SCAN_FIELDS);
    }

    @Test
    @DisplayName("UC-24: a student's only record in a category is never unusual")
    void aSingleRecordIsNeverUnusual() throws Exception {

        String token = loginNewStudent();

        Long only = aRecord(token, "9999.99", "Laptop");

        JsonNode scan = scanExpectingOk(token);

        assertThat(scan.get("flagged").asInt()).isZero();
        assertThat(storedFlagOf(only).isFlagged()).isFalse();
    }

    @Test
    @DisplayName("UC-24: the check runs in the student's own time zone")
    void datesAreInterpretedInTheApplicationZone() throws Exception {

        String token = loginNewStudent();

        LocalDate yesterday = today().minusDays(1);
        Long first = createTransaction(token, defaultCategoryId(FOOD), "25.00", yesterday, null);
        Long second = aRecord(token, "25.00", null);

        scanExpectingOk(token);

        assertThat(longValueFrom("SELECT DATEDIFF(?, ?)", today(), yesterday)).isEqualTo(1);
        assertThat(storedFlagOf(second).flagType()).isEqualTo("DUPLICATE");
        assertThat(storedFlagOf(first).flagType()).isEqualTo("NONE");
    }

    private static void assertThatCountsAreConsistent(JsonNode scan) {
        assertThat(scan.get("examined").asInt())
                .as("examined = flagged + cleared + unchanged, in %s", scan)
                .isEqualTo(scan.get("flagged").asInt()
                        + scan.get("cleared").asInt()
                        + scan.get("unchanged").asInt());
    }
}
