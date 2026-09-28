package com.campuscoin.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;

class AdminStatsApiIT extends AbstractAdminApiIT {

    private static final String STATUS_URL = ADMIN_USERS_URL + "/%d/status";
    private static final String PERSONAL_CATEGORIES_URL = "/api/v1/categories";
    private static final String TRANSACTIONS_URL = "/api/v1/transactions";

    private static final List<String> DOCUMENTED_STATS_FIELDS = List.of(
            "totalStudents", "activeStudents", "disabledStudents", "activeUsers30d",
            "totalTransactions", "totalExpenseLogged", "totalIncomeLogged", "totalBudgets",
            "totalTipsGenerated", "totalInsightsGenerated");

    private static final List<String> DOCUMENTED_RANKING_FIELDS = List.of(
            "categoryId", "categoryName", "type", "scope", "txnCount", "totalAmount",
            "distinctUsers");

    @Test
    @DisplayName("UC-23: the usage response carries exactly the view's ten aggregates")
    void theUsageResponseCarriesExactlyTheDocumentedFields() throws Exception {
        JsonNode stats = ok(HttpMethod.GET, ADMIN_STATS_URL, adminToken(), null);

        assertThat(stats.isObject()).isTrue();
        assertThat(fieldNamesOf(stats)).containsExactlyInAnyOrderElementsOf(DOCUMENTED_STATS_FIELDS);

        assertThat(allKeysIn(stats)).doesNotContain("userId", "user_id", "email", "fullName",
                "studentId", "student_id", "sessionToken");
    }

    @Test
    @DisplayName("UC-23: every count equals the table it is derived from")
    void everyCountEqualsTheTableItIsDerivedFrom() throws Exception {
        JsonNode stats = ok(HttpMethod.GET, ADMIN_STATS_URL, adminToken(), null);

        assertThat(stats.get("totalStudents").asLong()).isEqualTo(
                longValueFrom("SELECT COUNT(*) FROM users WHERE role = 'STUDENT'"));
        assertThat(stats.get("activeStudents").asLong()).isEqualTo(
                longValueFrom("SELECT COUNT(*) FROM users WHERE role = 'STUDENT' AND status = 'ACTIVE'"));
        assertThat(stats.get("disabledStudents").asLong()).isEqualTo(
                longValueFrom("SELECT COUNT(*) FROM users WHERE role = 'STUDENT' AND status = 'DISABLED'"));
        assertThat(stats.get("activeUsers30d").asLong()).isEqualTo(
                longValueFrom("SELECT COUNT(DISTINCT user_id) FROM user_sessions "
                        + "WHERE last_seen_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)"));
        assertThat(stats.get("totalTransactions").asLong()).isEqualTo(
                longValueFrom("SELECT COUNT(*) FROM transactions WHERE is_deleted = 0"));
        assertThat(stats.get("totalBudgets").asLong()).isEqualTo(
                longValueFrom("SELECT COUNT(*) FROM budgets"));
        assertThat(stats.get("totalTipsGenerated").asLong()).isEqualTo(
                longValueFrom("SELECT COUNT(*) FROM user_tips"));

        assertThat(stats.get("totalExpenseLogged").decimalValue()).isEqualByComparingTo(
                new BigDecimal(stringValueFrom(
                        "SELECT IFNULL(SUM(t.amount), 0) FROM transactions t "
                                + "JOIN categories c ON c.id = t.category_id "
                                + "WHERE t.is_deleted = 0 AND c.type = 'EXPENSE'")));
        assertThat(stats.get("totalIncomeLogged").decimalValue()).isEqualByComparingTo(
                new BigDecimal(stringValueFrom(
                        "SELECT IFNULL(SUM(t.amount), 0) FROM transactions t "
                                + "JOIN categories c ON c.id = t.category_id "
                                + "WHERE t.is_deleted = 0 AND c.type = 'INCOME'")));

        assertThat(stats.get("totalStudents").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(stats.get("totalTransactions").asLong()).isGreaterThan(0);
    }

    @Test
    @DisplayName("M12 locked: the insights figure is the table's own count and nothing in this module moves it")
    void theInsightsFigureIsTheTablesOwnCount() throws Exception {
        String token = adminToken();

        long insightsInTable = longValueFrom("SELECT COUNT(*) FROM insights");
        JsonNode stats = ok(HttpMethod.GET, ADMIN_STATS_URL, token, null);

        assertThat(stats.get("totalInsightsGenerated").asLong())
                .as("the figure is the table's, not a hard-coded zero")
                .isEqualTo(insightsInTable);

        Long studentId = registeredStudentId(randomEmail());
        String originalThreshold = stringValueFrom(
                "SELECT setting_value FROM system_settings WHERE setting_key = ?",
                "insight.spike_threshold_pct");

        try {
            patched(ADMIN_SETTINGS_URL + "/insight.spike_threshold_pct", token, Map.of("value", "41"));
            ok(HttpMethod.POST, STATUS_URL.formatted(studentId), token, Map.of("status", "ACTIVE"));

            assertThat(longValueFrom("SELECT COUNT(*) FROM insights"))
                    .as("no module 11 write generates an insight")
                    .isEqualTo(insightsInTable);
        } finally {
            patched(ADMIN_SETTINGS_URL + "/insight.spike_threshold_pct", token,
                    Map.of("value", originalThreshold));
        }

        assertThat(stringValueFrom("SELECT setting_value FROM system_settings WHERE setting_key = ?",
                "insight.spike_threshold_pct"))
                .as("the setting is left as it was found")
                .isEqualTo(originalThreshold);
    }

    @Test
    @DisplayName("UC-23: disabling a student moves the counts and leaves the total alone")
    void disablingAStudentMovesTheCounts() throws Exception {

        String email = randomEmail();
        Long studentId = registeredStudentId(email);
        String token = adminToken();

        JsonNode before = ok(HttpMethod.GET, ADMIN_STATS_URL, token, null);

        try {

            ok(HttpMethod.POST, STATUS_URL.formatted(studentId), token, Map.of("status", "DISABLED"));

            JsonNode after = ok(HttpMethod.GET, ADMIN_STATS_URL, token, null);

            assertThat(after.get("totalStudents").asLong())
                    .as("a disabled account is still a student account")
                    .isEqualTo(before.get("totalStudents").asLong());
            assertThat(after.get("activeStudents").asLong())
                    .isEqualTo(before.get("activeStudents").asLong() - 1);
            assertThat(after.get("disabledStudents").asLong())
                    .isEqualTo(before.get("disabledStudents").asLong() + 1);
        } finally {

            ok(HttpMethod.POST, STATUS_URL.formatted(studentId), token, Map.of("status", "ACTIVE"));
        }

        JsonNode restored = ok(HttpMethod.GET, ADMIN_STATS_URL, token, null);
        assertThat(restored.get("activeStudents").asLong())
                .isEqualTo(before.get("activeStudents").asLong());
    }

    @Test
    @DisplayName("UC-23: every ranking row carries exactly the documented fields")
    void rankingRowsCarryExactlyTheDocumentedFields() throws Exception {
        JsonNode ranking = ok(HttpMethod.GET, ADMIN_STATS_TOP_CATEGORIES_URL, adminToken(), null);

        assertThat(ranking.isArray()).isTrue();
        assertThat(ranking).isNotEmpty();
        for (JsonNode row : ranking) {
            assertThat(fieldNamesOf(row)).containsExactlyInAnyOrderElementsOf(DOCUMENTED_RANKING_FIELDS);
        }

        assertThat(allKeysIn(ranking)).doesNotContain("userId", "user_id", "email", "fullName",
                "studentId", "student_id");
    }

    @Test
    @DisplayName("UC-23: a category nobody has used still appears, with zeroes")
    void anUnusedCategoryStillAppears() throws Exception {

        String name = uniqueCategoryName();
        JsonNode created = created(ADMIN_CATEGORIES_URL, adminToken(),
                Map.of("name", name, "type", "EXPENSE"));
        Long categoryId = created.get("id").asLong();

        JsonNode ranking = ok(HttpMethod.GET, ADMIN_STATS_TOP_CATEGORIES_URL, adminToken(), null);
        JsonNode row = rowWithCategoryId(ranking, categoryId);

        assertThat(row).as("an unused category must still be ranked").isNotNull();
        assertThat(row.get("categoryName").asText()).isEqualTo(name);
        assertThat(row.get("scope").asText()).isEqualTo("DEFAULT");
        assertThat(row.get("txnCount").asLong()).isZero();
        assertThat(row.get("distinctUsers").asLong()).isZero();
        assertThat(row.get("totalAmount").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(ranking).anyMatch(ranked -> ranked.get("txnCount").asLong() > 0);
    }

    @Test
    @DisplayName("UC-23: a student's own category is told apart from a shared one")
    void apersonalCategoryIsToldApartFromASharedOne() throws Exception {

        String studentToken = studentToken();
        String personalName = uniqueCategoryName();

        JsonNode personal = created(PERSONAL_CATEGORIES_URL, studentToken,
                Map.of("name", personalName, "type", "EXPENSE"));
        Long personalId = personal.get("id").asLong();

        created(TRANSACTIONS_URL, studentToken, Map.of(
                "categoryId", personalId,
                "amount", "7.25",
                "txnDate", "2026-09-22",
                "description", "Filed under the module 11 suite's personal category."));

        JsonNode ranking = ok(HttpMethod.GET, ADMIN_STATS_TOP_CATEGORIES_URL, adminToken(), null);
        JsonNode row = rowWithCategoryId(ranking, personalId);

        assertThat(row).isNotNull();
        assertThat(row.get("scope").asText()).isEqualTo("PERSONAL");
        assertThat(row.get("txnCount").asLong()).isEqualTo(1);
        assertThat(row.get("distinctUsers").asLong()).isEqualTo(1);

        assertThat(scopesIn(ranking)).contains("DEFAULT", "PERSONAL");
    }

    @Test
    @DisplayName("UC-23: a used category's count and total match the transactions behind it")
    void aUsedCategoryMatchesTheTransactionsBehindIt() throws Exception {

        String studentToken = studentToken();
        String name = uniqueCategoryName();
        Long categoryId = created(ADMIN_CATEGORIES_URL, adminToken(),
                Map.of("name", name, "type", "EXPENSE")).get("id").asLong();

        created(TRANSACTIONS_URL, studentToken, Map.of(
                "categoryId", categoryId, "amount", "10.00", "txnDate", "2026-09-23"));
        created(TRANSACTIONS_URL, studentToken, Map.of(
                "categoryId", categoryId, "amount", "2.50", "txnDate", "2026-09-24"));

        JsonNode row = rowWithCategoryId(
                ok(HttpMethod.GET, ADMIN_STATS_TOP_CATEGORIES_URL, adminToken(), null), categoryId);

        assertThat(row).isNotNull();
        assertThat(row.get("txnCount").asLong()).isEqualTo(
                longValueFrom("SELECT COUNT(*) FROM transactions "
                        + "WHERE category_id = ? AND is_deleted = 0", categoryId));
        assertThat(row.get("totalAmount").decimalValue()).isEqualByComparingTo(
                new BigDecimal(stringValueFrom("SELECT IFNULL(SUM(amount), 0) FROM transactions "
                        + "WHERE category_id = ? AND is_deleted = 0", categoryId)));
        assertThat(row.get("distinctUsers").asLong()).isEqualTo(
                longValueFrom("SELECT COUNT(DISTINCT user_id) FROM transactions "
                        + "WHERE category_id = ? AND is_deleted = 0", categoryId));
    }

    @Test
    @DisplayName("UC-23: the ranking is ordered by use, with a tie-break that cannot swap")
    void theRankingIsOrderedByUse() throws Exception {
        JsonNode ranking = ok(HttpMethod.GET, ADMIN_STATS_TOP_CATEGORIES_URL, adminToken(), null);

        List<RankingKey> keys = rankingKeysOf(ranking);
        assertThat(keys)
                .as("txnCount DESC, totalAmount DESC, categoryId ASC")
                .isSortedAccordingTo(RankingKey.RANKING_ORDER);

        assertThat(keys.get(0).txnCount())
                .isGreaterThanOrEqualTo(keys.get(keys.size() - 1).txnCount());
    }

    @Test
    @DisplayName("UC-23: two identical calls return the same order")
    void twoIdenticalCallsReturnTheSameOrder() throws Exception {
        String token = adminToken();

        JsonNode first = ok(HttpMethod.GET, ADMIN_STATS_TOP_CATEGORIES_URL, token, null);
        JsonNode second = ok(HttpMethod.GET, ADMIN_STATS_TOP_CATEGORIES_URL, token, null);

        assertThat(categoryIdsIn(first)).isEqualTo(categoryIdsIn(second));
    }

    @Test
    @DisplayName("UC-23: the ranking covers every category, personal and shared alike")
    void theRankingCoversEveryCategory() throws Exception {

        JsonNode ranking = ok(HttpMethod.GET, ADMIN_STATS_TOP_CATEGORIES_URL, adminToken(), null);

        assertThat((long) categoryIdsIn(ranking).size()).isEqualTo(
                longValueFrom("SELECT COUNT(*) FROM categories"));
        assertThat(categoryIdsIn(ranking)).doesNotHaveDuplicates();
    }

    private static String uniqueCategoryName() {
        return "Suite Stats " + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<Long> categoryIdsIn(JsonNode ranking) {
        List<Long> ids = new ArrayList<>();
        ranking.forEach(row -> ids.add(row.get("categoryId").asLong()));
        return ids;
    }

    private static List<String> scopesIn(JsonNode ranking) {
        List<String> scopes = new ArrayList<>();
        ranking.forEach(row -> scopes.add(row.get("scope").asText()));
        return scopes;
    }

    private static JsonNode rowWithCategoryId(JsonNode ranking, Long categoryId) {
        for (JsonNode row : ranking) {
            if (row.get("categoryId").asLong() == categoryId) {
                return row;
            }
        }
        return null;
    }

    private static List<RankingKey> rankingKeysOf(JsonNode ranking) {
        List<RankingKey> keys = new ArrayList<>();
        ranking.forEach(row -> keys.add(new RankingKey(
                row.get("txnCount").asLong(),
                row.get("totalAmount").decimalValue(),
                row.get("categoryId").asLong())));
        return keys;
    }

    private record RankingKey(long txnCount, BigDecimal totalAmount, long categoryId) {

        private static final Comparator<RankingKey> RANKING_ORDER =
                Comparator.comparingLong(RankingKey::txnCount).reversed()
                        .thenComparing(Comparator.comparing(RankingKey::totalAmount).reversed())
                        .thenComparingLong(RankingKey::categoryId);
    }
}
