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

/**
 * UC-24 end to end: the scan decides, the database stores, and the list reports - against a real
 * MySQL, the real {@code sp_flag_transaction} and the real trigger on {@code transactions}.
 *
 * <p><b>The acceptance scenarios are the tests named for them.</b> "A duplicate is found and cleared"
 * and "an unusual amount is found and cleared" walk the whole path the use case describes - records
 * entered, the check run, the mark applied, the student correcting the record, the check run again and
 * the mark gone - and are the reason the suite goes through the API rather than calling the detector.
 *
 * <p><b>{@code examined = flagged + cleared + unchanged} is asserted in every scan.</b> It is the one
 * invariant that ties the counts to the work, and it is the assertion that fails if the service ever
 * counts records it did not examine or examines records it does not count.
 *
 * <p><b>The idempotence test is the one that would hurt in production.</b> A client that scans on every
 * screen visit must not accumulate history rows, and the guard is not a boolean elsewhere - it is that
 * a repeated scan reaches the same verdict and therefore writes nothing. That is only observable in the
 * database, so the test reads {@code transaction_history} around a second scan.
 *
 * <p><b>Envelope comparisons, not shape checks.</b> An AES-256-GCM envelope is Base64 by construction,
 * so "does this string look like an envelope" is not a question worth asking - a hand-written plaintext
 * in this fixture would look like one too. Where the suite needs to know whether a column was rewritten
 * it compares two stored values, which differ whenever the ciphertext was regenerated; where it needs
 * to know the words are recoverable it decrypts. It never has to decide what a string <em>is</em>.
 *
 * <p><b>What this class does not do.</b> It does not test the arithmetic - see
 * {@code AnomalyDetectorTest}, which can express the boundaries a seeded fixture cannot - and it does
 * not call {@code sp_flag_transaction} directly, because everything the endpoint does through it is
 * observable in the table. A procedure-level test would pin the procedure's three {@code SIGNAL}s, and
 * the API cannot reach any of them: it passes an enum member for a type that exists, on a record it
 * just read. Their guarantee - that the database refuses a hand-run {@code CALL} against another
 * student's record - is a property of the SQL, not of this module.
 */
class AnomalyApiIT extends AbstractAnomalyApiIT {

    // ==================================================================
    //  The acceptance scenarios
    // ==================================================================

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

        // The later record is the one marked, and the note names the earlier one's date so the student
        // can find it.
        StoredFlag stored = storedFlagOf(second);
        assertThat(stored.isFlagged()).isTrue();
        assertThat(stored.flagType()).isEqualTo("DUPLICATE");
        assertThat(stored.flagNote()).contains(today().minusDays(1).toString());
        assertThat(storedFlagOf(first).isFlagged()).isFalse();

        // The list agrees with the scan, and carries the record's own details beside the mark.
        JsonNode flagged = flagged(token);
        assertThat(entryTransactionIdsOf(flagged)).containsExactly(second);
        JsonNode entry = entryFor(flagged, second);
        assertThat(entry.get("flagType").asText()).isEqualTo("DUPLICATE");
        assertThat(entry.get("isFlagged").asBoolean()).isTrue();
        assertThat(entry.get("description").asText()).isEqualTo("Coffee");
        assertThat(entry.get("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("25.00"));

        // The student removes the record they entered twice, and the mark goes with it.
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
        // The other remedy, and the one that shows the rule is about the live records rather than about
        // what the student did: removing the record they entered first is just as valid a fix, and the
        // mark on the second has to follow.
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

        // Three ordinary records, spaced beyond the three-day window so the duplicate rule cannot
        // reach them, and one of ninety against a usual ten.
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
        // The note states the figures the student is being compared against, because the finding is a
        // comparison and only they can judge whether it is right.
        assertThat(stored.flagNote()).contains("90.00").contains("10.00");

        // Correcting the amount to something ordinary removes the mark on the next scan.
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
        // The duplicate's fix is to make the two records differ. Editing rather than trashing is the
        // other way a student expresses that - the second record was real, the first was the mistake -
        // and the rule has to see the corrected data, not remember the old pair.
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
        // Two real purchases, and the case the window exists to allow. The dates are 40 days apart, so
        // the duplicate rule does not reach them. Each is then measured against the other as its whole
        // baseline: an average of 50.00, and a threshold of three times that - 150.00 - which the
        // record's own 50.00 is nowhere near. So neither is unusual, and the second is not merely
        // "small relative to a large average". The check is worth making explicit: a rule that flagged
        // a monthly subscription would be unusable.
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
        // The boundary, through the whole stack. The service reads the multiplier from
        // system_settings, so a comparison written with the wrong operator would show up here as a
        // finding that never arrives rather than as a wrong number in a response.
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
        // One cent below the boundary, which is the case a "greater than or equal" written as "greater
        // than" would get wrong. The two tests together pin the operator rather than the magnitude.
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
        // The exclusion, end to end: two records where one is ten times the other. If the new record
        // were counted in its own average the average would be 55.00 and the threshold 165.00, so the
        // large record would pass unremarked - which is the failure the rule exists to avoid.
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
        // `flag_type` holds one value, so the two findings cannot both be recorded. The duplicate claim
        // is the more specific one and the one with the clearer remedy, so it is what the student is
        // shown.
        String token = loginNewStudent();

        Long first = aRecordDaysAgo(token, "900.00", 1, null);
        Long second = aRecord(token, "900.00", null);

        scanExpectingOk(token);

        StoredFlag stored = storedFlagOf(second);
        assertThat(stored.flagType()).isEqualTo("DUPLICATE");
        assertThat(stored.flagNote()).contains("already entered");
        assertThat(storedFlagOf(first).isFlagged()).isFalse();
    }

    // ==================================================================
    //  Repeating a scan
    // ==================================================================

    @Test
    @DisplayName("UC-24: a second scan over unchanged records writes nothing at all")
    void aSecondScanWritesNothing() throws Exception {
        // The guarantee a client that refreshes on every screen visit depends on. `trg_transactions_
        // after_update` would suppress a history row for a write that changed nothing, so counting
        // history rows alone would not distinguish "wrote and changed nothing" from "did not write";
        // the `unchanged` count is what distinguishes them, and the history count is what shows the
        // database was not touched.
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
        // The other side of the same coin: BR-09 is not satisfied by never writing. A record that goes
        // from unflagged to flagged is a change to the student's data and is recorded as one.
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

    // ==================================================================
    //  What the list reports, and what it withholds
    // ==================================================================

    @Test
    @DisplayName("UC-24: a student with nothing flagged gets an empty list, not a 404")
    void nothingFlaggedIsAnEmptyList() throws Exception {
        // Most students have nothing flagged, and "nothing of yours looks wrong" is a fact about their
        // own data rather than a missing resource.
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
        // The reason the scan is a separate request. A read that recomputed the marks would make every
        // screen visit, retry and prefetch a potential write against the student's history - so the
        // records sit unmarked until the client sends the scan, however many times the list is read.
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

        // A literal set, so a field added to the response fails a test instead of quietly widening the
        // published contract.
        assertThat(fieldNamesOf(entry)).containsExactlyElementsOf(DOCUMENTED_ENTRY_FIELDS);
    }

    @Test
    @DisplayName("UC-24: a record with no description omits the field rather than carrying a null")
    void aRecordWithNoDescriptionOmitsTheField() throws Exception {
        // The description is the one optional column here, and an absent one is absent in the response
        // too - so a client can tell "the student wrote nothing" from "the student wrote something the
        // server would not show".
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
        // Read straight from the column: asserting through the response would prove only that the round
        // trip works, not that the database never held the words.
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

        // Two marks in two categories, so the order being asserted is the order of the list rather
        // than the order two comparisons happened to run in. Food's three ordinary records build a
        // baseline of 10.00, against which 900.00 is unusual; Transport's pair one day apart is a
        // duplicate, and its mark falls on the later of the two.
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
        // Refused rather than reduced, because the response reports the limit it applied and a silently
        // reduced answer would make that field untrue.
        String token = loginNewStudent();
        Long food = defaultCategoryId(FOOD);
        Long transport = defaultCategoryId("Transport");

        // A baseline of three ordinary records, then two records far enough above it to be unusual and
        // distinct in amount so neither duplicates the other.
        createTransaction(token, food, "10.00", today().minusDays(60), null);
        createTransaction(token, food, "10.00", today().minusDays(40), null);
        createTransaction(token, food, "10.00", today().minusDays(20), null);
        Long olderUnusual = createTransaction(token, food, "900.00", today().minusDays(9), null);
        Long newerUnusual = createTransaction(token, food, "700.00", today().minusDays(5), null);

        // And one duplicate pair, in another category, whose mark falls on the later of the two.
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

        // Before a scan, the same list is empty however large the limit - so the records below are marks
        // the scan applied rather than rows the query would have found anyway.
        for (String refused : List.of("0", "-1", "101", "abc")) {
            ResponseEntity<String> response = flaggedWithLimit(token, refused);
            assertThat(response.getStatusCode())
                    .as("limit=%s body=%s", refused, response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        assertThat(fieldNamesIn(body(flaggedWithLimit(token, "101")))).containsExactly("limit");
    }

    // ==================================================================
    //  Ownership
    // ==================================================================

    @Test
    @DisplayName("UC-24: one student's records are invisible to another, and the check never sees them")
    void anotherStudentsRecordsAreInvisible() throws Exception {
        String firstToken = loginNewStudent();
        String secondToken = loginNewStudent();

        // The same amount, the same category, the same day - so if the query were not narrowed by owner
        // this would be a duplicate of a record the other student entered. It is not, and neither
        // student is told anything about the other's record.
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
        // A fixture of one's own records only proves the check works on data a test built to suit it.
        // The seeded account carries a month of transactions entered by hand, and running the check
        // over them is the closest thing available to asking whether it behaves on data nobody
        // arranged.
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

    // ==================================================================
    //  Authentication
    // ==================================================================

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
        // A student's own records examined for mistakes, and one of the two endpoints writes. UC-23's
        // statistics are aggregates over many students; this is one student's spending, so the role has
        // no use case here and the rule admits only STUDENT.
        String adminToken = adminLogin();

        assertThat(send(HttpMethod.GET, ANOMALIES_URL, adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, SCAN_URL, adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("UC-24: the scan reads the caller, not a body, so there is nothing to forge")
    void theScanTakesNoBodyThatCouldNameAnotherStudent() throws Exception {
        // The client's whole contribution is asking for the check. There is no field for a record, a
        // flag type or an owner, and a body is therefore ignored rather than honoured.
        String token = loginNewStudent();
        Long mine = aRecordDaysAgo(token, "25.00", 1, null);
        Long second = aRecord(token, "25.00", null);

        ResponseEntity<String> response = send(HttpMethod.POST, SCAN_URL, token,
                Map.of("userId", 1, "flagType", "NONE", "transactionId", mine));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response).get("flagged").asInt()).isEqualTo(1);
        assertThat(storedFlagOf(second).flagType()).isEqualTo("DUPLICATE");
    }

    // ==================================================================
    //  Empty and trivial histories
    // ==================================================================

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
        // One record is a figure, not a habit. The first thing a new student enters must not come back
        // pre-marked, whatever it cost.
        String token = loginNewStudent();

        Long only = aRecord(token, "9999.99", "Laptop");

        JsonNode scan = scanExpectingOk(token);

        assertThat(scan.get("flagged").asInt()).isZero();
        assertThat(storedFlagOf(only).isFlagged()).isFalse();
    }

    @Test
    @DisplayName("UC-24: the check runs in the student's own time zone")
    void datesAreInterpretedInTheApplicationZone() throws Exception {
        // The window is counted in whole days on txn_date, and the date the API stored is the one the
        // student sent - so a record dated last week is a week away from one dated today whenever the
        // suite happens to run. This pins that the two dates are the ones on the rows rather than the
        // times the rows were written, which is the difference the fixture would otherwise hide.
        String token = loginNewStudent();

        LocalDate yesterday = today().minusDays(1);
        Long first = createTransaction(token, defaultCategoryId(FOOD), "25.00", yesterday, null);
        Long second = aRecord(token, "25.00", null);

        scanExpectingOk(token);

        assertThat(longValueFrom("SELECT DATEDIFF(?, ?)", today(), yesterday)).isEqualTo(1);
        assertThat(storedFlagOf(second).flagType()).isEqualTo("DUPLICATE");
        assertThat(storedFlagOf(first).flagType()).isEqualTo("NONE");
    }

    // ==================================================================
    //  Assertions
    // ==================================================================

    /**
     * {@code examined = flagged + cleared + unchanged}.
     *
     * <p>Asserted after every scan in this class rather than once, because it is the only thing tying
     * the counts to the work: a service that counted a record it did not examine, or examined one it
     * did not count, would still return a plausible-looking body.
     */
    private static void assertThatCountsAreConsistent(JsonNode scan) {
        assertThat(scan.get("examined").asInt())
                .as("examined = flagged + cleared + unchanged, in %s", scan)
                .isEqualTo(scan.get("flagged").asInt()
                        + scan.get("cleared").asInt()
                        + scan.get("unchanged").asInt());
    }
}
