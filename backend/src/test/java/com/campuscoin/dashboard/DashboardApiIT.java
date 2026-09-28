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

import com.campuscoin.common.setting.SettingReader;
import com.fasterxml.jackson.databind.JsonNode;

class DashboardApiIT extends AbstractDashboardApiIT {

    private static final String TIPS_URL = "/api/v1/tips";

    private static final String ADMIN_SETTINGS_URL = "/api/v1/admin/settings";

    private static final String BOOKMARKS_URL = "/api/v1/bookmarks";

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

        assertThat(dashboard.has("topCategory")).isFalse();

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

        JsonNode summary = dashboard(token).get("summary");
        assertThat(new BigDecimal(summary.get("monthlySavingsGoal").asText()))
                .isEqualByComparingTo("0.00");
        assertThat(summary.has("savingsGoalPct")).isFalse();

        setSavingsGoal(token, "100.00");

        summary = dashboard(token).get("summary");
        assertThat(new BigDecimal(summary.get("monthlySavingsGoal").asText()))
                .isEqualByComparingTo("100.00");

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

        assertThat(new BigDecimal(summary.get("savingsGoalPct").asText()))
                .isEqualByComparingTo("-40.00");
    }

    @Test
    @DisplayName("UC-12 B2: the highest-spending expense category is named, with its icon and colour")
    void topCategoryIsTheLargestExpenseCategory() throws Exception {
        String token = loginNewStudent();
        createTransaction(token, defaultCategoryId(FOOD), "24.00", thisMonthOn(3), "Campus Cafe");
        createTransaction(token, defaultCategoryId(TRANSPORT), "40.00", thisMonthOn(5), "Bus pass");

        JsonNode top = dashboard(token).get("topCategory");

        assertThat(top.get("categoryName").asText()).isEqualTo(TRANSPORT);
        assertThat(new BigDecimal(top.get("totalAmount").asText())).isEqualByComparingTo("40.00");

        assertThat(top.get("categoryIcon").asText()).isEqualTo("bus");
        assertThat(top.get("categoryColor").asText()).isEqualTo("#3B82F6");
    }

    @Test
    @DisplayName("UC-12 B2: a month with income but no spending has no top category")
    void incomeAloneDoesNotProduceATopCategory() throws Exception {
        String token = loginNewStudent();
        createTransaction(token, defaultCategoryId(INCOME_CATEGORY), "200.00", thisMonthOn(1),
                "Monthly allowance");

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

    @Test
    @DisplayName("UC-12 B3: tips are the current month's, in the order the view ranked them")
    void tipsAreTheCurrentMonthsAndAlreadyRanked() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long food = defaultCategoryId(FOOD);

        setBudget(token, food, "30.00");
        createTransaction(token, food, "24.00", thisMonthOn(6), "Campus Cafe");
        createTransaction(token, defaultCategoryId(TRANSPORT), "60.00", thisMonthOn(7), "Bus pass");

        generateTips(userId, thisMonth(), 3);

        JsonNode tips = dashboard(token).get("tips");
        assertThat(tips).isNotEmpty();

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

        assertThat(countOf("SELECT COUNT(*) FROM user_tips WHERE id = ?", dismissed)).isEqualTo(1);
        assertThat(tipIdsOf(dashboard(token).get("tips"))).doesNotContain(dismissed);
    }

    @Test
    @DisplayName("UC-12 B3: a tip about no single category omits categoryId rather than sending null")
    void tipsWithoutACategoryOmitTheField() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        generateTips(userId, thisMonth(), 3);

        JsonNode tips = dashboard(token).get("tips");
        assertThat(tips).hasSize(1);
        assertThat(tips.get(0).has("categoryId")).isFalse();
        assertThat(tips.get(0).get("state").asText()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("UC-12 B3: only the audiences a student may see are returned, never an admin notice")
    void announcementsAreFilteredByAudience() throws Exception {
        String token = loginNewStudent();

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

            assertThat(returned.indexOf(newer)).isLessThan(returned.indexOf(older));
        } finally {
            deleteAnnouncement(older);
            deleteAnnouncement(newer);
        }
    }

    @Test
    @DisplayName("UC-12: the seeded demo account's dashboard reports its month's figures")
    void theDemoDashboardReportsTheSeededMonth() throws Exception {
        String token = login(SEEDED_STUDENT_EMAIL);

        JsonNode dashboard = dashboard(token);
        JsonNode summary = dashboard.get("summary");

        assertThat(new BigDecimal(summary.get("totalIncome").asText()))
                .isEqualByComparingTo("260.00");
        assertThat(new BigDecimal(summary.get("totalExpense").asText()))
                .isEqualByComparingTo("189.00");
        assertThat(new BigDecimal(summary.get("netAmount").asText()))
                .isEqualByComparingTo("71.00");

        assertThat(new BigDecimal(summary.get("monthlySavingsGoal").asText()))
                .isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(summary.get("monthlyAllowanceBaseline").asText()))
                .isEqualByComparingTo("200.00");
        assertThat(new BigDecimal(summary.get("savingsGoalPct").asText()))
                .isEqualByComparingTo("71.00");

        JsonNode top = dashboard.get("topCategory");
        assertThat(top.get("categoryName").asText()).isEqualTo("Hostel/Rent");
        assertThat(new BigDecimal(top.get("totalAmount").asText())).isEqualByComparingTo("120.00");

        JsonNode tips = dashboard.get("tips");
        assertThat(tips).isNotEmpty();
        assertThat(tips).allSatisfy(tip ->
                assertThat(tip.get("state").asText()).isEqualTo("NEW"));

        assertThat(tipIdsOf(tips)).hasSizeGreaterThan(1);
        assertThat(tips.get(0).has("categoryId")).isFalse();

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

        long maxDashboard = longValuesFrom(
                "SELECT CAST(setting_value AS UNSIGNED) FROM system_settings WHERE setting_key = ?",
                "tips.max_dashboard").get(0);
        assertThat(tips.size()).isLessThanOrEqualTo((int) maxDashboard);
    }

    @Test
    @DisplayName("BR-14: the administrator's tips.max_dashboard bounds the student's dashboard")
    void theDashboardNeverExceedsTheConfiguredLimit() throws Exception {
        String studentToken = loginNewStudent();
        Long userId = userIdOf(studentToken);
        String adminToken = adminLogin();

        int originalValue = settingAsInt(SettingReader.TIPS_MAX_DASHBOARD);
        try {

            Long food = defaultCategoryId(FOOD);
            setBudget(studentToken, food, "30.00");
            createTransaction(studentToken, food, "120.00", thisMonthOn(3),
                    "Groceries for the month");
            createTransaction(studentToken, defaultCategoryId(ACADEMICS), "60.00",
                    thisMonthOn(4), "Textbooks and printing");
            createTransaction(studentToken, defaultCategoryId(TRANSPORT), "38.00",
                    thisMonthOn(5), "Bus and train passes");
            setSavingsGoal(studentToken, "100.00");

            assertThat(send(HttpMethod.POST, TIPS_URL + "/generate", studentToken, null)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);

            int storedAfterFirstRun = tipIdsFor(userId, thisMonth()).size();
            assertThat(storedAfterFirstRun)
                    .as("the first run filled the configured limit and no more")
                    .isGreaterThan(0);

            createTransaction(studentToken, defaultCategoryId(ENTERTAINMENT), "5000.00",
                    thisMonthOn(6), "Concert tickets, front row");
            createTransaction(studentToken, defaultCategoryId(MISCELLANEOUS), "4000.00",
                    thisMonthOn(7), "Replacement laptop charger and cables");

            assertThat(send(HttpMethod.POST, TIPS_URL + "/generate", studentToken, null)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);

            List<Long> storedTipIds = tipIdsFor(userId, thisMonth());
            assertThat(storedTipIds.size())
                    .as("the two runs stored %d row(s), which must exceed the configured maximum "
                            + "for the read-time bound to be the thing under test",
                            storedTipIds.size())
                    .isGreaterThan(3);

            List<Long> rankedTipIds = rankedTipIdsFor(userId, thisMonth());
            assertThat(rankedTipIds).hasSameSizeAs(storedTipIds);

            for (int configured : List.of(1, 3, 5)) {
                setMaxDashboard(adminToken, configured);

                assertThat(settingAsInt("tips.max_dashboard"))
                        .as("the setting really holds %d before the dashboard is read", configured)
                        .isEqualTo(configured);

                JsonNode dashboard = dashboard(studentToken);
                JsonNode tips = dashboard.get("tips");

                assertThat(tips.size())
                        .as("dashboard showed %d tips under tips.max_dashboard=%d",
                                tips.size(), configured)
                        .isLessThanOrEqualTo(configured);

                assertThat(tips.size()).isLessThanOrEqualTo(storedTipIds.size());

                List<Long> shown = tipIdsOf(tips);
                assertThat(shown).isEqualTo(rankedTipIds.subList(0, shown.size()));
            }

            assertThat(tipIdsFor(userId, thisMonth())).containsExactlyElementsOf(storedTipIds);

            setMaxDashboard(adminToken, 1);
            JsonNode tipsScreen = body(send(HttpMethod.GET, TIPS_URL, studentToken, null));
            assertThat(tipsScreen.get("tips").size()).isEqualTo(storedTipIds.size());

            Long hiddenTipId = rankedTipIds.get(rankedTipIds.size() - 1);
            assertThat(tipIdsOf(dashboard(studentToken).get("tips"))).doesNotContain(hiddenTipId);

            ResponseEntity<String> saved = send(HttpMethod.POST, BOOKMARKS_URL, studentToken,
                    requestOf("itemType", "TIP", "itemId", hiddenTipId,
                            "note", "Keep this one for next month"));
            assertThat(saved.getStatusCode())
                    .as("bookmark body=%s", saved.getBody())
                    .isEqualTo(HttpStatus.CREATED);
            assertThat(body(saved).get("tipId").asLong()).isEqualTo(hiddenTipId);
            assertThat(countOf("SELECT COUNT(*) FROM bookmarks WHERE user_id = ? AND tip_id = ?",
                    userId, hiddenTipId))
                    .as("the bookmark survived the setting change it was created under")
                    .isEqualTo(1);
        } finally {

            setMaxDashboard(adminToken, originalValue);
        }
    }

    @Test
    @DisplayName("BR-14: a dashboard with no tips shows none, whatever the limit is")
    void anEmptyTipMonthStaysEmpty() throws Exception {
        String token = loginNewStudent();
        String adminToken = adminLogin();

        int originalValue = settingAsInt(SettingReader.TIPS_MAX_DASHBOARD);
        try {
            setMaxDashboard(adminToken, 5);

            assertThat(dashboard(token).get("tips")).isEmpty();
        } finally {
            setMaxDashboard(adminToken, originalValue);
        }
    }

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

        setSavingsGoal(token, "150.00");

        JsonNode dashboard = dashboard(token);

        assertThat(fieldNamesOf(dashboard)).containsExactlyInAnyOrderElementsOf(
                DOCUMENTED_TOP_LEVEL_FIELDS);
        assertThat(fieldNamesOf(dashboard.get("summary")))
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_SUMMARY_FIELDS);
        assertThat(fieldNamesOf(dashboard.get("topCategory")))
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_TOP_CATEGORY_FIELDS);

        assertThat(dashboard.toString()).doesNotContain("userId", "user_id", "createdBy",
                "created_by");
    }

    @Test
    @DisplayName("UC-12: the dashboard takes no month parameter and always reports the current month")
    void theMonthIsNotSelectable() throws Exception {
        String token = loginNewStudent();

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

        assertThat(tipStateOf(tipId)).isEqualTo(stateBefore);
        assertThat(countOf("SELECT COUNT(*) FROM notifications WHERE user_id = ?", userId))
                .isEqualTo(notificationsBefore);
    }

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

    private void setMaxDashboard(String adminToken, int value) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.PATCH,
                ADMIN_SETTINGS_URL + "/" + SettingReader.TIPS_MAX_DASHBOARD, adminToken,
                requestOf("value", String.valueOf(value)));
        assertThat(response.getStatusCode())
                .as("setting body=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(body(response).get("value").asText()).isEqualTo(String.valueOf(value));
    }

    private int settingAsInt(String key) throws Exception {
        return Math.toIntExact(longValuesFrom(
                "SELECT CAST(setting_value AS UNSIGNED) FROM system_settings WHERE setting_key = ?",
                key).get(0));
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
