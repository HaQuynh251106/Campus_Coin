package com.campuscoin.tips;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

class TipsApiIT extends AbstractTipsApiIT {

    @Test
    @DisplayName("UC-18: with no month named, the current month is answered and named in the response")
    void listingWithoutAMonthAnswersTheCurrentMonth() throws Exception {
        String token = loginNewStudent();

        JsonNode response = currentTips(token);

        assertThat(response.get("periodMonth").asText()).isEqualTo(asMonth(thisMonth()));
        assertThat(fieldNamesOf(response)).containsExactlyInAnyOrderElementsOf(DOCUMENTED_LIST_FIELDS);
    }

    @Test
    @DisplayName("UC-18: a month with no generated tips answers 200 with an empty list, not 404")
    void aMonthWithNoTipsIsAnEmptyList() throws Exception {
        String token = loginNewStudent();

        JsonNode response = tips(token, asMonth(monthBefore(thisMonth())));

        assertThat(response.get("tips")).isEmpty();
        assertThat(response.get("periodMonth").asText()).isEqualTo(asMonth(monthBefore(thisMonth())));
    }

    @Test
    @DisplayName("UC-18: a month that was never generated cannot be made to produce tips by reading it")
    void readingAMonthDoesNotGenerateTips() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long transport = defaultCategoryId(TRANSPORT);
        LocalDate lastMonth = monthBefore(thisMonth());

        spendInCategoryWithoutBudget(token, transport, "40.00", lastMonth);
        assertThat(countOf("SELECT COUNT(*) FROM user_tips WHERE user_id = ?", userId)).isZero();

        tips(token, asMonth(lastMonth));

        assertThat(countOf("SELECT COUNT(*) FROM user_tips WHERE user_id = ?", userId)).isZero();
    }

    @Test
    @DisplayName("UC-18: a tip is returned with its rendered title and body and no internal columns")
    void aTipCarriesItsRenderedTextAndNothingInternal() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(TRANSPORT);
        spendInCategoryWithoutBudget(token, categoryId, "40.00", thisMonth());

        ResponseEntity<String> generated = generateTipsViaApi(token);
        assertThat(generated.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode response = body(generated);
        assertThat(tipIdsOf(response)).isNotEmpty();

        JsonNode tip = response.get("tips").get(0);
        assertThat(fieldNamesOf(tip))
                .as("a tip must publish exactly the documented fields")
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_TIP_FIELDS);

        assertThat(tip.get("title").asText()).isNotBlank().contains(TRANSPORT);
        assertThat(tip.get("body").asText()).isNotBlank().contains("40.00");
        assertThat(tip.get("state").asText()).isEqualTo("NEW");
        assertThat(tip.get("categoryId").asLong()).isEqualTo(categoryId);
    }

    @Test
    @DisplayName("UC-18 A2: a month that is not a month is refused with a field error, not read as another")
    void anUnparseableMonthIsRefused() throws Exception {
        String token = loginNewStudent();

        for (String bad : new String[] {"2026-13", "2026-9", "not-a-month", "2026-09-01", "2026"}) {
            ResponseEntity<String> response = send(HttpMethod.GET, TIPS_URL + "?month=" + bad,
                    token, null);
            assertThat(response.getStatusCode())
                    .as("month=%s body=%s", bad, response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");

            assertThat(body(response).get("fieldErrors").get(0).get("field").asText())
                    .isEqualTo("month");
        }
    }

    @Test
    @DisplayName("UC-18: a month's tips lead with the pinned one, and the rest keep the database's order")
    void pinnedTipsLeadTheList() throws Exception {
        String token = loginNewStudent();
        Long food = defaultCategoryId(FOOD);
        Long transport = defaultCategoryId(TRANSPORT);
        spendInCategoryWithoutBudget(token, food, "60.00", thisMonth());
        spendInCategoryWithoutBudget(token, transport, "20.00", thisMonth());

        JsonNode before = body(generateTipsViaApi(token));
        assertThat(tipIdsOf(before).size()).as("two unbudgeted categories produce two tips").isEqualTo(2);

        List<Long> generatedOrder = tipIdsOf(before);
        Long toPin = generatedOrder.get(generatedOrder.size() - 1);

        assertThat(changeTipState(token, toPin, "PINNED").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(tipIdsOf(currentTips(token)).get(0)).isEqualTo(toPin);
        assertThat(tipStatesOf(currentTips(token)).get(0)).isEqualTo("PINNED");
    }

    @Test
    @DisplayName("UC-18: pinning writes the state and the timestamp together, as ck_tip_state requires")
    void pinningStampsTheStateAndItsTimestamp() throws Exception {
        String token = loginNewStudent();
        Long tipId = aGeneratedTip(token);

        ResponseEntity<String> response = changeTipState(token, tipId, "PINNED");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response).get("state").asText()).isEqualTo("PINNED");
        assertThat(tipStateOf(tipId)).isEqualTo("PINNED");

        assertThat(tipColumnOf(tipId, "pinned_at")).isNotNull();
        assertThat(tipColumnOf(tipId, "dismissed_at")).isNull();
    }

    @Test
    @DisplayName("UC-18: clearing a pin returns the tip to NEW and removes the timestamp")
    void clearingAPinReturnsTheTipToNew() throws Exception {
        String token = loginNewStudent();
        Long tipId = aGeneratedTip(token);
        changeTipState(token, tipId, "PINNED");

        ResponseEntity<String> response = changeTipState(token, tipId, "NEW");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response).get("state").asText()).isEqualTo("NEW");
        assertThat(tipColumnOf(tipId, "pinned_at")).isNull();
        assertThat(tipColumnOf(tipId, "dismissed_at")).isNull();
    }

    @Test
    @DisplayName("UC-18: a dismissed tip leaves the list and stays gone, even after regenerating")
    void aDismissedTipStaysDismissedAcrossAGeneration() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(TRANSPORT);
        spendInCategoryWithoutBudget(token, categoryId, "40.00", thisMonth());
        Long tipId = body(generateTipsViaApi(token)).get("tips").get(0).get("id").asLong();

        ResponseEntity<String> dismissed = changeTipState(token, tipId, "DISMISSED");
        assertThat(dismissed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(dismissed).get("state").asText()).isEqualTo("DISMISSED");
        assertThat(tipColumnOf(tipId, "dismissed_at")).isNotNull();
        assertThat(tipColumnOf(tipId, "pinned_at")).isNull();

        assertThat(tipIdsOf(currentTips(token))).doesNotContain(tipId);

        generateTipsViaApi(token);

        assertThat(tipIdsOf(currentTips(token))).doesNotContain(tipId);
        assertThat(countOf("SELECT COUNT(*) FROM user_tips WHERE user_id = ? AND id = ?",
                userIdOf(token), tipId)).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-18: a dismissed tip's month drops out of the months list, because it would show nothing")
    void aMonthWhoseOnlyTipWasDismissedLeavesTheMonthsList() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(TRANSPORT);
        spendInCategoryWithoutBudget(token, categoryId, "40.00", thisMonth());
        Long tipId = body(generateTipsViaApi(token)).get("tips").get(0).get("id").asLong();

        assertThat(months(token).get("months")).isNotEmpty();

        changeTipState(token, tipId, "DISMISSED");

        List<String> offered = new ArrayList<>();
        months(token).get("months").forEach(month -> offered.add(month.asText()));
        assertThat(offered).doesNotContain(asMonth(thisMonth()));
    }

    @Test
    @DisplayName("UC-18: asking for the state a tip already holds succeeds and changes nothing")
    void askingForTheStateATipAlreadyHoldsIsNotAnError() throws Exception {
        String token = loginNewStudent();
        Long tipId = aGeneratedTip(token);

        ResponseEntity<String> first = changeTipState(token, tipId, "PINNED");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String pinnedAtAfterFirstPin = tipColumnOf(tipId, "pinned_at");
        assertThat(pinnedAtAfterFirstPin).isNotNull();

        ResponseEntity<String> second = changeTipState(token, tipId, "PINNED");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(second).get("id").asLong()).isEqualTo(tipId);
        assertThat(body(second).get("state").asText()).isEqualTo("PINNED");

        assertThat(tipColumnOf(tipId, "pinned_at"))
                .as("a second pin must not restamp the tip")
                .isEqualTo(pinnedAtAfterFirstPin);
    }

    @Test
    @DisplayName("UC-18: a dismissed tip cannot be brought back")
    void aDismissedTipCannotBeRestored() throws Exception {
        String token = loginNewStudent();
        Long tipId = aGeneratedTip(token);
        changeTipState(token, tipId, "DISMISSED");

        for (String attempted : new String[] {"PINNED", "NEW"}) {
            ResponseEntity<String> response = changeTipState(token, tipId, attempted);
            assertThat(response.getStatusCode())
                    .as("attempted=%s body=%s", attempted, response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
            assertThat(body(response).get("fieldErrors").get(0).get("field").asText())
                    .isEqualTo("state");
        }

        assertThat(tipStateOf(tipId)).isEqualTo("DISMISSED");
    }

    @Test
    @DisplayName("UC-18: a missing or unknown state is refused by validation")
    void anUnknownStateIsRefused() throws Exception {
        String token = loginNewStudent();
        Long tipId = aGeneratedTip(token);

        ResponseEntity<String> missing = send(HttpMethod.POST, TIPS_URL + "/" + tipId + "/state",
                token, Map.of());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(missing)).isEqualTo("VALIDATION_ERROR");

        ResponseEntity<String> unknown = changeTipState(token, tipId, "ARCHIVED");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("BR-02: one student's tips are not reachable from another student's token")
    void tipsAreScopedToTheirOwner() throws Exception {
        String owner = loginNewStudent();
        Long ownerCategory = defaultCategoryId(TRANSPORT);
        spendInCategoryWithoutBudget(owner, ownerCategory, "40.00", thisMonth());
        Long tipId = body(generateTipsViaApi(owner)).get("tips").get(0).get("id").asLong();

        String other = loginNewStudent();

        assertThat(tipIdsOf(currentTips(other))).isEmpty();
        assertThat(months(other).get("months")).isEmpty();

        ResponseEntity<String> response = changeTipState(other, tipId, "PINNED");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCodeOf(response)).isEqualTo("NOT_FOUND");

        assertThat(tipStateOf(tipId)).isEqualTo("NEW");
    }

    @Test
    @DisplayName("BR-02: a tip that does not exist answers the same 404 as one belonging to somebody else")
    void anUnknownTipIsNotFound() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response = changeTipState(token, 999_999_999L, "PINNED");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCodeOf(response)).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("UC-18: no token is unauthenticated, and no endpoint takes a user id")
    void tipsRequireAToken() {
        assertThat(send(HttpMethod.GET, TIPS_URL, null, null).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.GET, TIP_MONTHS_URL, null, null).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, TIPS_GENERATE_URL, null, null).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);

        ResponseEntity<String> ignoredUserId =
                send(HttpMethod.GET, TIPS_URL + "?userId=2", null, null);
        assertThat(ignoredUserId.getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("UC-18: generating is idempotent - a second run neither duplicates nor restores a tip")
    void generatingTwiceChangesNothing() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long transport = defaultCategoryId(TRANSPORT);
        Long food = defaultCategoryId(FOOD);
        spendInCategoryWithoutBudget(token, transport, "40.00", thisMonth());
        spendInCategoryWithoutBudget(token, food, "60.00", thisMonth());

        JsonNode first = body(generateTipsViaApi(token));
        Long pinnedTip = tipIdsOf(first).get(0);
        Long dismissedTip = tipIdsOf(first).get(1);
        changeTipState(token, pinnedTip, "PINNED");
        changeTipState(token, dismissedTip, "DISMISSED");

        int rowsBefore = countOf("SELECT COUNT(*) FROM user_tips WHERE user_id = ?", userId);

        JsonNode second = body(generateTipsViaApi(token));

        assertThat(countOf("SELECT COUNT(*) FROM user_tips WHERE user_id = ?", userId))
                .isEqualTo(rowsBefore);

        assertThat(tipIdsOf(second).get(0)).isEqualTo(pinnedTip);
        assertThat(tipIdsOf(second)).doesNotContain(dismissedTip);
        assertThat(tipStateOf(pinnedTip)).isEqualTo("PINNED");
        assertThat(tipStateOf(dismissedTip)).isEqualTo("DISMISSED");
    }

    @Test
    @DisplayName("UC-18: generating returns the resulting list rather than an acknowledgement")
    void generatingReturnsTheListItProduced() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(TRANSPORT);
        spendInCategoryWithoutBudget(token, categoryId, "40.00", thisMonth());

        JsonNode response = body(generateTipsViaApi(token));

        assertThat(fieldNamesOf(response)).containsExactlyInAnyOrderElementsOf(DOCUMENTED_LIST_FIELDS);
        assertThat(response.get("periodMonth").asText()).isEqualTo(asMonth(thisMonth()));
        assertThat(tipIdsOf(response)).isNotEmpty();

        assertThat(tipIdsOf(response)).isEqualTo(tipIdsOf(currentTips(token)));
    }

    @Test
    @DisplayName("UC-18 A1: a student with no data this month gets the generator's \"too little data\" tip")
    void aStudentWithNoDataGetsTheGenericTip() throws Exception {
        String token = loginNewStudent();

        JsonNode response = body(generateTipsViaApi(token));

        assertThat(tipIdsOf(response)).isNotEmpty();
        assertThat(tipStatesOf(response)).containsOnly("NEW");
    }

    @Test
    @DisplayName("UC-18: the months list offers exactly the months that would return a tip")
    void theMonthsListMatchesTheMonthsThatHaveTips() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(TRANSPORT);
        LocalDate lastMonth = monthBefore(thisMonth());
        LocalDate twoMonthsAgo = monthBefore(lastMonth);

        spendInCategoryWithoutBudget(token, categoryId, "40.00", twoMonthsAgo);
        spendInCategoryWithoutBudget(token, categoryId, "30.00", lastMonth);
        generateTipsFor(userId, twoMonthsAgo, 3);
        generateTipsFor(userId, lastMonth, 3);

        JsonNode response = months(token);

        assertThat(fieldNamesOf(response))
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_MONTHS_FIELDS);
        List<String> offered = new ArrayList<>();
        response.get("months").forEach(month -> offered.add(month.asText()));

        assertThat(offered).containsExactly(asMonth(lastMonth), asMonth(twoMonthsAgo));

        for (String month : offered) {
            assertThat(tipIdsOf(tips(token, month))).as("month %s is not empty", month).isNotEmpty();
        }
    }

    @Test
    @DisplayName("UC-18: a student who has never had tips generated is offered no months")
    void aStudentWithNoTipsIsOfferedNoMonths() throws Exception {
        String token = loginNewStudent();

        JsonNode response = months(token);

        assertThat(response.get("months")).isEmpty();
    }

    @Test
    @DisplayName("UC-18: two pins of the same tip racing do not leave it in an inconsistent state")
    void twoPinsOfOneTipSettleOnOneState() throws Exception {
        String token = loginNewStudent();
        Long tipId = aGeneratedTip(token);

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<String>>> results = new ArrayList<>();
            for (int attempt = 0; attempt < 2; attempt++) {
                results.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return changeTipState(token, tipId, "PINNED");
                }));
            }
            for (Future<ResponseEntity<String>> result : results) {

                assertThat(result.get(30, TimeUnit.SECONDS).getStatusCode())
                        .as("a concurrent pin of an already-pinned tip must not be refused")
                        .isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(countOf("SELECT COUNT(*) FROM user_tips WHERE id = ?", tipId)).isEqualTo(1);
        assertThat(tipStateOf(tipId)).isEqualTo("PINNED");
        assertThat(tipColumnOf(tipId, "pinned_at")).isNotNull();
        assertThat(tipColumnOf(tipId, "dismissed_at")).isNull();
    }

    @Test
    @DisplayName("UC-18: generating while the same student pins settles without a duplicate tip")
    void generatingWhilePinningDoesNotDuplicateATip() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(TRANSPORT);
        spendInCategoryWithoutBudget(token, categoryId, "40.00", thisMonth());
        Long tipId = body(generateTipsViaApi(token)).get("tips").get(0).get("id").asLong();

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<String>>> results = new ArrayList<>();
            results.add(pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return generateTipsViaApi(token);
            }));
            results.add(pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return changeTipState(token, tipId, "PINNED");
            }));
            for (Future<ResponseEntity<String>> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS).getStatusCode())
                        .as("neither the pin nor the generate may fail because of the other")
                        .isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(countOf("SELECT COUNT(*) FROM user_tips WHERE user_id = ? AND period_month = ?",
                userId, thisMonth())).isEqualTo(1);
        assertThat(tipStateOf(tipId)).isEqualTo("PINNED");
    }

    private Long aGeneratedTip(String token) throws Exception {
        Long categoryId = defaultCategoryId(TRANSPORT);
        spendInCategoryWithoutBudget(token, categoryId, "40.00", thisMonth());
        return body(generateTipsViaApi(token)).get("tips").get(0).get("id").asLong();
    }
}
