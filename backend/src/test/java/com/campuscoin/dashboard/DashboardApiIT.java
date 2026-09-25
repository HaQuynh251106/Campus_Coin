package com.campuscoin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * UC-12 over the real HTTP stack: request, security filter, controller, service, DAO, MySQL.
 *
 * <p>Every test registers its own student with a random address, so no test depends on another's rows
 * and the seeded accounts and announcements are only ever read. Where a test needs a row the API has
 * no route for yet - an administrator-only announcement, a pinned tip - it produces it the way the
 * module that owns it will, and removes it afterwards.
 *
 * <p><b>The tests that matter most are the ones about what is <em>not</em> in the response.</b> This is
 * the one endpoint that returns a named student's income, spending, top category and personal advice
 * in a single payload, so a missing filter here does not leak an identifier - it leaks a financial
 * picture. The suite therefore asserts four separate refusals: another student's figures, an
 * announcement addressed to administrators, a tip the student dismissed, and a month's tips shown
 * under another month's heading.
 */
class DashboardApiIT extends AbstractDashboardApiIT {

    // ==================================================================
    //  UC-12 B1 - the month's totals
    // ==================================================================

    @Test
    @DisplayName("UC-12: a new student's dashboard opens with zeroed totals and no top category")
    void newStudentDashboardIsEmptyButValid() throws Exception {
        String token = loginNewStudent();

        JsonNode dashboard = dashboard(token);

        assertThat(dashboard.get("periodMonth").asText()).isEqualTo(monthKey(thisMonth()));

        JsonNode summary = dashboard.get("summary");
        assertThat(new BigDecimal(summary.get("totalIncome").asText()))
                .isEqualByComparingTo("0.00");
        assertThat(new BigDecimal(summary.get("totalExpense").asText()))
                .isEqualByComparingTo("0.00");
        assertThat(new BigDecimal(summary.get("netAmount").asText()))
                .isEqualByComparingTo("0.00");
        assertThat(summary.get("currency").asText()).isEqualTo("USD");

        // A student who has spent nothing has no highest-spending category to name. The block is
        // absent rather than a placeholder row carrying a zero.
        assertThat(dashboard.has("topCategory")).isFalse();

        // The two list blocks are always present, and empty is a real answer.
        assertThat(dashboard.get("tips")).isEmpty();
    }

    @Test
    @DisplayName("UC-12 B1: the totals are the month's live transactions, split by category type")
    void totalsCountIncomeAndExpenseByCategoryType() throws Exception {
        String token = loginNewStudent();

        Long allowance = defaultCategoryId(INCOME_CATEGORY);
        Long food = defaultCategoryId(FOOD);

        createTransaction(token, allowance, "200.00", thisMonthOn(1), "Monthly allowance");
        createTransaction(token, food, "24.50", thisMonthOn(6), "Campus Cafe");

        JsonNode summary = dashboard(token).get("summary");

        assertThat(new BigDecimal(summary.get("totalIncome").asText()))
                .isEqualByComparingTo("200.00");
        assertThat(new BigDecimal(summary.get("totalExpense").asText()))
                .isEqualByComparingTo("24.50");
        // BR-10: the net figure is the difference, computed by the database rather than here.
        assertThat(new BigDecimal(summary.get("netAmount").asText()))
                .isEqualByComparingTo("175.50");
    }

    @Test
    @DisplayName("BR-09: a transaction in the trash is excluded from the dashboard totals")
    void softDeletedTransactionDropsOutOfTheTotals() throws Exception {
        String token = loginNewStudent();
        Long food = defaultCategoryId(FOOD);

        createTransaction(token, food, "30.00", thisMonthOn(4), "Campus Cafe");
        Long toDelete = createTransaction(token, food, "12.00", thisMonthOn(5), "Late snack");

        assertThat(new BigDecimal(dashboard(token).get("summary").get("totalExpense").asText()))
                .isEqualByComparingTo("42.00");

        ResponseEntity<String> deleted = send(HttpMethod.DELETE,
                TRANSACTIONS_URL + "/" + toDelete, token, null);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The row still exists as far as the table is concerned; the view is what excludes it.
        assertThat(countOf("SELECT COUNT(*) FROM transactions WHERE id = ?", toDelete)).isEqualTo(1);
        assertThat(new BigDecimal(dashboard(token).get("summary").get("totalExpense").asText()))
                .isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("UC-12 B1 / VĐ-04: savingsGoalPct is absent with no goal and reported with one")
    void savingsGoalPercentageAppearsOnlyWhenAGoalIsSet() throws Exception {
        String token = loginNewStudent();
        createTransaction(token, defaultCategoryId(INCOME_CATEGORY), "200.00", thisMonthOn(1),
                "Monthly allowance");

        // No goal: the view emits NULL, and a substituted zero would read as "made no progress".
        JsonNode summary = dashboard(token).get("summary");
        assertThat(new BigDecimal(summary.get("monthlySavingsGoal").asText()))
                .isEqualByComparingTo("0.00");
        assertThat(summary.has("savingsGoalPct")).isFalse();

        setSavingsGoal(token, "100.00");

        summary = dashboard(token).get("summary");
        assertThat(new BigDecimal(summary.get("monthlySavingsGoal").asText()))
                .isEqualByComparingTo("100.00");
        // 200.00 net against a 100.00 goal is 200.00%.
        assertThat(new BigDecimal(summary.get("savingsGoalPct").asText()))
                .isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("UC-12 B1: a month that spent more than it earned reports a negative goal percentage")
    void negativeGoalPercentageIsReportedRatherThanClamped() throws Exception {
        String token = loginNewStudent();
        setSavingsGoal(token, "100.00");
        createTransaction(token, defaultCategoryId(INCOME_CATEGORY), "50.00", thisMonthOn(1),
                "Part-time shift");
        createTransaction(token, defaultCategoryId(FOOD), "90.00", thisMonthOn(6), "Campus Cafe");

        JsonNode summary = dashboard(token).get("summary");

        assertThat(new BigDecimal(summary.get("netAmount").asText()))
                .isEqualByComparingTo("-40.00");
        // A month that went backwards against the goal is a real answer to the same question, not a
        // value to floor at zero.
        assertThat(new BigDecimal(summary.get("savingsGoalPct").asText()))
                .isEqualByComparingTo("-40.00");
    }

    // ==================================================================
    //  UC-12 B2 - the top category
    // ==================================================================

    @Test
    @DisplayName("UC-12 B2: the highest-spending expense category is named, with its icon and colour")
    void topCategoryIsTheLargestExpenseCategory() throws Exception {
        String token = loginNewStudent();
        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(3), "Campus Cafe");
        createTransaction(token, defaultCategoryId(TRANSPORT), "40.00", thisMonthOn(5), "Bus pass");

        JsonNode top = dashboard(token).get("topCategory");

        assertThat(top.get("categoryName").asText()).isEqualTo(TRANSPORT);
        assertThat(new BigDecimal(top.get("totalAmount").asText())).isEqualByComparingTo("40.00");
        // The view publishes no icon or colour, so the DAO joins `categories` for them; these are the
        // seeded values, which is what proves the join found the right row.
        assertThat(top.get("categoryIcon").asText()).isEqualTo("bus");
        assertThat(top.get("categoryColor").asText()).isEqualTo("#3B82F6");
    }

    @Test
    @DisplayName("UC-12 B2: a month with income but no spending has no top category")
    void incomeAloneDoesNotProduceATopCategory() throws Exception {
        String token = loginNewStudent();
        createTransaction(token, defaultCategoryId(INCOME_CATEGORY), "200.00", thisMonthOn(1),
                "Monthly allowance");

        // The view ranks only EXPENSE categories, so income cannot become the "highest spending" one.
        assertThat(dashboard(token).has("topCategory")).isFalse();
    }

    @Test
    @DisplayName("UC-12 B2: the top category counts only live transactions")
    void topCategoryIgnoresTrashedTransactions() throws Exception {
        String token = loginNewStudent();
        Long food = defaultCategoryId(FOOD);
        Long transport = defaultCategoryId(TRANSPORT);

        createTransaction(token, food, "10.00", thisMonthOn(3), "Snack");
        Long bigTransport = createTransaction(token, transport, "50.00", thisMonthOn(5), "Bus pass");

        assertThat(dashboard(token).get("topCategory").get("categoryName").asText())
                .isEqualTo(TRANSPORT);

        send(HttpMethod.DELETE, TRANSACTIONS_URL + "/" + bigTransport, token, null);

        assertThat(dashboard(token).get("topCategory").get("categoryName").asText())
                .isEqualTo(FOOD);
    }

    // ==================================================================
    //  UC-12 B3 - tips
    // ==================================================================

    @Test
    @DisplayName("UC-12 B3: tips are the current month's, in the order the view ranked them")
    void tipsAreTheCurrentMonthsAndAlreadyRanked() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long food = defaultCategoryId(FOOD);

        // A budget at 80% produces a NEAR_BUDGET tip, and a large unbudgeted category produces a
        // NO_BUDGET_SET one - two tips with different scores, so the order is observable.
        setBudget(token, food, "30.00");
        createTransaction(token, food, "24.00", thisMonthOn(6), "Campus Cafe");
        createTransaction(token, defaultCategoryId(TRANSPORT), "60.00", thisMonthOn(7), "Bus pass");

        generateTips(userId, thisMonth(), 3);

        JsonNode tips = dashboard(token).get("tips");
        assertThat(tips).isNotEmpty();

        // The order the dashboard returns must be the order the view ranked them: read the view's own
        // display_order rather than restating the rule, so this asserts the dashboard reports the
        // ranking instead of inventing a second one.
        List<Long> expected = longValuesFrom("""
                SELECT tip_id FROM v_dashboard_tips
                 WHERE user_id = ? AND period_month = ? AND state <> 'DISMISSED'
                 ORDER BY display_order ASC, tip_id ASC
                """, userId, thisMonth());

        assertThat(tipIdsOf(tips)).isEqualTo(expected);

        for (JsonNode tip : tips) {
            assertThat(fieldNamesOf(tip))
                    .as("a tip carries only the documented fields")
                    .allMatch(DOCUMENTED_TIP_FIELDS::contains);
        }
    }

    @Test
    @DisplayName("UC-12 B3: a tip generated for another month is not shown on this month's dashboard")
    void tipsFromAnotherMonthAreNotShown() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Campus Cafe");

        LocalDate lastMonth = thisMonth().minusMonths(1);
        generateTips(userId, lastMonth, 3);

        // The tip exists - which is the point. v_dashboard_tips has no time filter of its own, so
        // without the DAO's month predicate these rows would appear under this month's heading.
        assertThat(tipIdsFor(userId, lastMonth)).isNotEmpty();
        assertThat(dashboard(token).get("tips")).isEmpty();
    }

    @Test
    @DisplayName("UC-12 B3 / BR-14: a pinned tip is shown first")
    void pinnedTipsLeadTheList() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long food = defaultCategoryId(FOOD);

        setBudget(token, food, "30.00");
        createTransaction(token, food, "24.00", thisMonthOn(6), "Campus Cafe");
        createTransaction(token, defaultCategoryId(TRANSPORT), "60.00", thisMonthOn(7), "Bus pass");
        generateTips(userId, thisMonth(), 3);

        List<Long> generated = tipIdsFor(userId, thisMonth());
        assertThat(generated).hasSizeGreaterThan(1);

        Long lastOnTheList = generated.get(generated.size() - 1);
        assertThat(dashboard(token).get("tips").get(0).get("id").asLong())
                .isNotEqualTo(lastOnTheList);

        pinTip(lastOnTheList);

        JsonNode tips = dashboard(token).get("tips");
        assertThat(tips.get(0).get("id").asLong()).isEqualTo(lastOnTheList);
        assertThat(tips.get(0).get("state").asText()).isEqualTo("PINNED");
    }

    @Test
    @DisplayName("UC-12 B3: a dismissed tip is removed from the dashboard for good")
    void dismissedTipsNeverComeBack() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Campus Cafe");
        generateTips(userId, thisMonth(), 3);

        List<Long> before = tipIdsOf(dashboard(token).get("tips"));
        assertThat(before).hasSize(1);
        Long dismissed = before.get(0);

        dismissTip(dismissed);

        // The row is still there - the view's `state <> 'DISMISSED'` is what removes it - and it is
        // not merely reordered to the end.
        assertThat(countOf("SELECT COUNT(*) FROM user_tips WHERE id = ?", dismissed)).isEqualTo(1);
        assertThat(tipIdsOf(dashboard(token).get("tips"))).doesNotContain(dismissed);
    }

    @Test
    @DisplayName("UC-12 B3: a tip about no single category omits categoryId rather than sending null")
    void tipsWithoutACategoryOmitTheField() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        // No transactions at all: the only rule that fires is the generic "too little data" tip, whose
        // category_id is NULL on the table.
        generateTips(userId, thisMonth(), 3);

        JsonNode tips = dashboard(token).get("tips");
        assertThat(tips).hasSize(1);
        assertThat(tips.get(0).has("categoryId")).isFalse();
        assertThat(tips.get(0).get("state").asText()).isEqualTo("NEW");
    }

    // ==================================================================
    //  UC-12 B3 - announcements
    // ==================================================================

    @Test
    @DisplayName("UC-12 B3: only the audiences a student may see are returned, never an admin notice")
    void announcementsAreFilteredByAudience() throws Exception {
        String token = loginNewStudent();

        // The view applies the active flag and the window but no audience filter, so this is the one
        // filter the module has to add. A notice written for administrators on a student's dashboard
        // would be a disclosure, not a styling bug.
        Long adminOnly = insertAnnouncement("Administrator maintenance window", "WARNING", "ADMINS",
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1), true);
        Long forEveryone = insertAnnouncement("Library extended hours", "INFO", "ALL",
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1), true);
        Long forStudents = insertAnnouncement("Scholarship applications open", "SUCCESS", "STUDENTS",
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1), true);

        try {
            List<Long> returned = announcementIdsOf(dashboard(token).get("announcements"));

            assertThat(returned).contains(forEveryone, forStudents);
            assertThat(returned).doesNotContain(adminOnly);
        } finally {
            deleteAnnouncement(adminOnly);
            deleteAnnouncement(forEveryone);
            deleteAnnouncement(forStudents);
        }
    }

    @Test
    @DisplayName("UC-12 B3: inactive and out-of-window announcements are not shown")
    void announcementsOutsideTheirWindowAreNotShown() throws Exception {
        String token = loginNewStudent();

        LocalDateTime now = LocalDateTime.now();
        Long inactive = insertAnnouncement("Switched off", "INFO", "STUDENTS",
                now.minusDays(2), now.plusDays(2), false);
        Long notStarted = insertAnnouncement("Starts next week", "INFO", "STUDENTS",
                now.plusDays(7), now.plusDays(14), true);
        Long finished = insertAnnouncement("Ended yesterday", "INFO", "STUDENTS",
                now.minusDays(14), now.minusDays(1), true);
        Long live = insertAnnouncement("Running now", "INFO", "STUDENTS",
                now.minusHours(1), now.plusHours(1), true);

        try {
            List<Long> returned = announcementIdsOf(dashboard(token).get("announcements"));

            assertThat(returned).contains(live);
            assertThat(returned).doesNotContain(inactive, notStarted, finished);
        } finally {
            deleteAnnouncement(inactive);
            deleteAnnouncement(notStarted);
            deleteAnnouncement(finished);
            deleteAnnouncement(live);
        }
    }

    @Test
    @DisplayName("UC-12 B3: an open-ended announcement is live, and omits endsAt")
    void openEndedAnnouncementIsLiveAndHasNoEnd() throws Exception {
        String token = loginNewStudent();
        Long openEnded = insertAnnouncement("Open-ended notice", "INFO", "STUDENTS",
                LocalDateTime.now().minusHours(1), null, true);

        try {
            JsonNode found = announcementById(dashboard(token).get("announcements"), openEnded);

            assertThat(found).isNotNull();
            // ck_ann_window permits a null end, and the view reads that as "still running".
            assertThat(found.has("endsAt")).isFalse();
            assertThat(found.get("severity").asText()).isEqualTo("INFO");
        } finally {
            deleteAnnouncement(openEnded);
        }
    }

    @Test
    @DisplayName("UC-12 B3: the seeded welcome announcements are visible to a new student")
    void seededAnnouncementsAreVisible() throws Exception {
        String token = loginNewStudent();

        JsonNode announcements = dashboard(token).get("announcements");
        List<String> titles = new java.util.ArrayList<>();
        announcements.forEach(announcement -> titles.add(announcement.get("title").asText()));

        // Seeded by 05_seed.sql as STUDENTS, active, now → +90 and +60 days. A dashboard that showed
        // nothing here would mean the window or the audience filter was wrong.
        assertThat(titles).contains("Welcome to Campus Coin");
    }

    @Test
    @DisplayName("UC-12 B3: announcements are ordered newest first")
    void announcementsAreOrderedNewestFirst() throws Exception {
        String token = loginNewStudent();

        LocalDateTime now = LocalDateTime.now();
        Long older = insertAnnouncement("Posted earlier", "INFO", "STUDENTS",
                now.minusDays(3), now.plusDays(10), true);
        Long newer = insertAnnouncement("Posted later", "INFO", "STUDENTS",
                now.minusHours(1), now.plusDays(10), true);

        try {
            List<Long> returned = announcementIdsOf(dashboard(token).get("announcements"));

            // The view has no ORDER BY of its own, so the order is the DAO's.
            assertThat(returned.indexOf(newer)).isLessThan(returned.indexOf(older));
        } finally {
            deleteAnnouncement(older);
            deleteAnnouncement(newer);
        }
    }

    // ==================================================================
    //  The seeded demo account
    //
    //  Read-only, and it has to stay that way. The seeded student is shared by every test in the
    //  suite, and an HTTP write is its own transaction that no rollback can undo - so a test here
    //  that recorded a transaction would change the figures the assertions in this section expect.
    //  (It did: a top-category test written against the demo account failed its sibling with
    //  319.00 where 189.00 was expected.) A test that needs to change the data registers its own
    //  student instead, which is what every other test in this class does.
    // ==================================================================

    @Test
    @DisplayName("UC-12: the seeded demo account's dashboard reports its month's figures")
    void theDemoDashboardReportsTheSeededMonth() throws Exception {
        String token = login(SEEDED_STUDENT_EMAIL);

        JsonNode dashboard = dashboard(token);
        JsonNode summary = dashboard.get("summary");

        // db/06_demo.sql: income 200.00 + 60.00, spending 120.00 + 8.00 + 24.00 + 12.00 + 25.00.
        assertThat(new BigDecimal(summary.get("totalIncome").asText()))
                .isEqualByComparingTo("260.00");
        assertThat(new BigDecimal(summary.get("totalExpense").asText()))
                .isEqualByComparingTo("189.00");
        assertThat(new BigDecimal(summary.get("netAmount").asText()))
                .isEqualByComparingTo("71.00");
        // The saving goal is seeded at 100.00 and the allowance baseline at 200.00.
        assertThat(new BigDecimal(summary.get("monthlySavingsGoal").asText()))
                .isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(summary.get("monthlyAllowanceBaseline").asText()))
                .isEqualByComparingTo("200.00");
        assertThat(new BigDecimal(summary.get("savingsGoalPct").asText()))
                .isEqualByComparingTo("71.00");

        // Dorm rent is the largest single expense and Hostel/Rent the largest category total.
        JsonNode top = dashboard.get("topCategory");
        assertThat(top.get("categoryName").asText()).isEqualTo("Hostel/Rent");
        assertThat(new BigDecimal(top.get("totalAmount").asText())).isEqualByComparingTo("120.00");

        // Three tips exist for this month, generated by the demo script, all still NEW.
        JsonNode tips = dashboard.get("tips");
        assertThat(tips).isNotEmpty();
        assertThat(tips).allSatisfy(tip ->
                assertThat(tip.get("state").asText()).isEqualTo("NEW"));

        // The savings-goal tip belongs to no category, so it omits the field while the others carry
        // it. This is the one shape a demo-account dashboard exercises that a controlled fixture
        // does not: a mixed list.
        assertThat(tipIdsOf(tips)).hasSizeGreaterThan(1);
        assertThat(tips.get(0).has("categoryId")).isFalse();

        // Both seeded announcements are live for a student, and the seeded category icon and colour
        // reach the response - which is what proves the join to `categories` found the row.
        assertThat(announcementIdsOf(dashboard.get("announcements"))).isNotEmpty();
        assertThat(top.get("categoryIcon").asText()).isEqualTo("home");
        assertThat(top.get("categoryColor").asText()).isEqualTo("#EF4444");
    }

    @Test
    @DisplayName("UC-12 B3: the tip block is bounded by the configured dashboard limit")
    void theTipBlockIsBounded() throws Exception {
        String token = login(SEEDED_STUDENT_EMAIL);
        Long userId = userIdOf(token);

        JsonNode tips = dashboard(token).get("tips");

        // The list is exactly the month's non-dismissed tips, and it is bounded by the setting
        // BR-14's generator reads (`tips.max_dashboard`, three by default). The dashboard shows what
        // the procedure generated rather than generating more, so it is not an unbounded list.
        assertThat(tips.size()).isEqualTo(countOf(
                "SELECT COUNT(*) FROM user_tips WHERE user_id = ? AND period_month = ? "
                        + "AND state <> 'DISMISSED'", userId, thisMonth()));

        long maxDashboard = longValuesFrom(
                "SELECT CAST(setting_value AS UNSIGNED) FROM system_settings WHERE setting_key = ?",
                "tips.max_dashboard").get(0);
        assertThat(tips.size()).isLessThanOrEqualTo((int) maxDashboard);
    }

    // ==================================================================
    //  Ownership and security
    // ==================================================================

    @Test
    @DisplayName("BR-02: one student's dashboard never contains another student's figures")
    void dashboardShowsOnlyTheCallersOwnFigures() throws Exception {
        String tokenA = loginNewStudent();
        String tokenB = loginNewStudent();

        createTransaction(tokenA, defaultCategoryId(FOOD), "11.00", thisMonthOn(3), "A's lunch");
        createTransaction(tokenB, defaultCategoryId(TRANSPORT), "77.00", thisMonthOn(4), "B's bus pass");

        JsonNode dashboardA = dashboard(tokenA);
        JsonNode dashboardB = dashboard(tokenB);

        assertThat(new BigDecimal(dashboardA.get("summary").get("totalExpense").asText()))
                .isEqualByComparingTo("11.00");
        assertThat(new BigDecimal(dashboardB.get("summary").get("totalExpense").asText()))
                .isEqualByComparingTo("77.00");

        assertThat(dashboardA.get("topCategory").get("categoryName").asText()).isEqualTo(FOOD);
        assertThat(dashboardB.get("topCategory").get("categoryName").asText()).isEqualTo(TRANSPORT);
    }

    @Test
    @DisplayName("Section 7.5: an administrator token is refused with 403")
    void administratorsAreRefused() throws Exception {
        String adminToken = adminLogin();

        ResponseEntity<String> response = send(HttpMethod.GET, DASHBOARD_URL, adminToken, null);

        // The dashboard returns a named student's income and spending. An administrator has no route
        // here and no use case for one.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("Section 7.5: the dashboard requires a token")
    void anonymousCallersAreRefused() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, DASHBOARD_URL, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Section 7.5: a malformed token is refused rather than treated as anonymous")
    void malformedTokenIsRefused() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.GET, DASHBOARD_URL, "not-a-jwt", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Section 7.2: no response carries the owner's identifier")
    void theResponseNeverNamesTheOwner() throws Exception {
        String token = loginNewStudent();
        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Campus Cafe");
        // A goal, so that the one nullable field of the summary is present too. Without it
        // savingsGoalPct is legitimately absent - asserted by its own test - and this check would
        // only be comparing the fields a student with no goal happens to see.
        setSavingsGoal(token, "150.00");

        JsonNode dashboard = dashboard(token);

        assertThat(fieldNamesOf(dashboard)).containsExactlyInAnyOrderElementsOf(
                DOCUMENTED_TOP_LEVEL_FIELDS);
        assertThat(fieldNamesOf(dashboard.get("summary")))
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_SUMMARY_FIELDS);
        assertThat(fieldNamesOf(dashboard.get("topCategory")))
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_TOP_CATEGORY_FIELDS);

        // The views carry user_id and the announcements view carries created_by; neither may appear.
        assertThat(dashboard.toString()).doesNotContain("userId", "user_id", "createdBy",
                "created_by");
    }

    @Test
    @DisplayName("UC-12: the dashboard takes no month parameter and always reports the current month")
    void theMonthIsNotSelectable() throws Exception {
        String token = loginNewStudent();

        // A `?month=` would be a promise the views cannot keep: v_dashboard_summary and
        // v_top_category_current_month both derive their month from the database's own clock. Rather
        // than answer a January question with September's figures, the endpoint ignores the parameter
        // and states which month it answered for.
        ResponseEntity<String> response = send(HttpMethod.GET,
                DASHBOARD_URL + "?month=2020-01", token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(body(response).get("periodMonth").asText()).isEqualTo(monthKey(thisMonth()));
    }

    @Test
    @DisplayName("UC-12: reading the dashboard writes nothing")
    void readingTheDashboardChangesNoState() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(6), "Campus Cafe");
        generateTips(userId, thisMonth(), 3);

        Long tipId = tipIdsFor(userId, thisMonth()).get(0);
        String stateBefore = tipStateOf(tipId);
        int notificationsBefore = countOf(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ?", userId);

        for (int attempt = 0; attempt < 3; attempt++) {
            dashboard(token);
        }

        // A dashboard is the kind of screen where a careless implementation marks things seen or
        // records a view. Every one of those would be a write behind a GET.
        assertThat(tipStateOf(tipId)).isEqualTo(stateBefore);
        assertThat(countOf("SELECT COUNT(*) FROM notifications WHERE user_id = ?", userId))
                .isEqualTo(notificationsBefore);
    }

    // ==================================================================
    //  Helpers
    // ==================================================================

    private void setSavingsGoal(String token, String goal) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.PATCH, PROFILE_URL, token,
                requestOf("monthlySavingsGoal", goal));
        assertThat(response.getStatusCode())
                .as("profile body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private void setBudget(String token, Long categoryId, String limit) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/budgets", token, requestOf(
                "categoryId", categoryId, "limitAmount", limit, "periodMonth", monthKey(thisMonth())));
        assertThat(response.getStatusCode())
                .as("budget body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private static List<Long> tipIdsOf(JsonNode tips) {
        List<Long> ids = new java.util.ArrayList<>();
        tips.forEach(tip -> ids.add(tip.get("id").asLong()));
        return ids;
    }

    private static List<Long> announcementIdsOf(JsonNode announcements) {
        List<Long> ids = new java.util.ArrayList<>();
        announcements.forEach(announcement -> ids.add(announcement.get("id").asLong()));
        return ids;
    }

    private static JsonNode announcementById(JsonNode announcements, Long id) {
        for (JsonNode announcement : announcements) {
            if (announcement.get("id").asLong() == id) {
                return announcement;
            }
        }
        return null;
    }
}
