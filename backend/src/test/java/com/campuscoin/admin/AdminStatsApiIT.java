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

/**
 * Endpoints 60–61: the system-wide usage figures (UC-23).
 *
 * <p><b>These are the only two reads in the module that publish money, and the way they are tested is
 * shaped by that.</b> {@code totalExpenseLogged}, {@code totalIncomeLogged} and each ranking row's
 * {@code totalAmount} are sums over plaintext {@code transactions.amount} - the aggregate exposure
 * recorded as OB-013. So the property that has to hold is <em>not</em> that money is absent; it is that
 * every money value is a sum across the whole system and no field identifies a student. The field-set
 * assertions are exact for that reason: a row of this ranking with a {@code userId} on it would be the
 * change that matters, and it would fail here.
 *
 * <p><b>Both figures are cross-checked against the tables they are derived from, live.</b>
 * {@code v_admin_usage_stats} is ten independent subqueries and a test that only asserted "the numbers
 * are non-negative" would pass against a view wired to the wrong column. So each count is read back
 * with the SQL the view uses and compared, and one test drives a real state change (disabling a student
 * through the user endpoint) and re-reads, which is what shows the figures follow the database rather
 * than being captured once.
 *
 * <p><b>{@code totalInsightsGenerated} is not zero, and the module 12 lock is pinned as "nothing in
 * this build writes the table" rather than as "the table is empty".</b> The integration database is
 * built from {@code db/merged/campuscoin_full.sql}, which appends {@code db/06_demo.sql}, and that seed
 * calls {@code sp_generate_monthly_insight} once for each of three demo months for the first student -
 * so {@code insights} holds three rows before any test runs. UC-17 is still locked in the sense that
 * matters: no Java in this build reads or writes the table, and no administrative procedure touches it.
 * What the test therefore asserts is the part that can be observed, and both halves of it are
 * falsifiable - the endpoint's figure is the view's own subquery over the table (not a literal the DAO
 * could have hard-coded), and no write this module can make moves it.
 */
class AdminStatsApiIT extends AbstractAdminApiIT {

    private static final String STATUS_URL = ADMIN_USERS_URL + "/%d/status";
    private static final String PERSONAL_CATEGORIES_URL = "/api/v1/categories";
    private static final String TRANSACTIONS_URL = "/api/v1/transactions";

    /**
     * The ten aggregate fields, as a literal.
     *
     * <p>Every component of {@code v_admin_usage_stats} and nothing else. No per-student field could be
     * added without failing this, which is the point: the money here is defensible only while it is
     * purely aggregate.
     */
    private static final List<String> DOCUMENTED_STATS_FIELDS = List.of(
            "totalStudents", "activeStudents", "disabledStudents", "activeUsers30d",
            "totalTransactions", "totalExpenseLogged", "totalIncomeLogged", "totalBudgets",
            "totalTipsGenerated", "totalInsightsGenerated");

    /** The seven ranking fields, as a literal; {@code scope} is what makes the ranking readable. */
    private static final List<String> DOCUMENTED_RANKING_FIELDS = List.of(
            "categoryId", "categoryName", "type", "scope", "txnCount", "totalAmount",
            "distinctUsers");

    // ==================================================================
    //  60 — GET /api/v1/admin/stats
    // ==================================================================

    @Test
    @DisplayName("UC-23: the usage response carries exactly the view's ten aggregates")
    void theUsageResponseCarriesExactlyTheDocumentedFields() throws Exception {
        JsonNode stats = ok(HttpMethod.GET, ADMIN_STATS_URL, adminToken(), null);

        assertThat(stats.isObject()).isTrue();
        assertThat(fieldNamesOf(stats)).containsExactlyInAnyOrderElementsOf(DOCUMENTED_STATS_FIELDS);
        // The aggregate row publishes no identifier of any kind - not a student, not a session. A
        // `userId` here would turn a system-wide sum into somebody's data.
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

        // The two sums, compared as decimals rather than as doubles: `SUM(amount)` is DECIMAL and a
        // binary comparison could disagree with it in the last place, which is exactly the kind of
        // difference a money figure must not have.
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

        // The non-triviality of the two checks above: the seeded students and transactions exist, so
        // the figures are not comparing zero against zero.
        assertThat(stats.get("totalStudents").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(stats.get("totalTransactions").asLong()).isGreaterThan(0);
    }

    @Test
    @DisplayName("M12 locked: the insights figure is the table's own count and nothing in this module moves it")
    void theInsightsFigureIsTheTablesOwnCount() throws Exception {
        String token = adminToken();

        // The seed generated three insights (see the class javadoc), so the assertion is not "zero" -
        // it is that the endpoint reports the table's actual count rather than a number of its own. The
        // two are read independently, so a view wired to a literal or to another table fails here.
        long insightsInTable = longValueFrom("SELECT COUNT(*) FROM insights");
        JsonNode stats = ok(HttpMethod.GET, ADMIN_STATS_URL, token, null);

        assertThat(stats.get("totalInsightsGenerated").asLong())
                .as("the figure is the table's, not a hard-coded zero")
                .isEqualTo(insightsInTable);

        // And the module 12 lock, stated as the falsifiable half: the administrative writes this module
        // ships cannot reach the table. Two are run, chosen because they are the two nearest to it -
        // `sp_admin_set_threshold` writes `system_settings`, and `insight.spike_threshold_pct` is one of
        // the keys `sp_generate_tips`/`v_category_spend_trend` read, so if module 12 ever generates on a
        // settings change this is where it would show; and `sp_set_user_status` writes the very account
        // whose insights these are. The count is re-read after both.
        //
        // The setting is restored to the value it was read with, in a `finally`, rather than left
        // changed: `system_settings` is shared container state, the same rows the module 9 and module 6
        // suites read, and a threshold left at a test's value is exactly the cross-suite contamination
        // the shared container makes possible. Restoring is cheap and makes this test order-independent.
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
        // The pairing test that makes the cross-check above mean something ongoing: the figures track a
        // change made through another endpoint in this module. `totalStudents` must not move - the
        // account still exists - while the two status counts swap a unit between them.
        String email = randomEmail();
        Long studentId = registeredStudentId(email);
        String token = adminToken();

        JsonNode before = ok(HttpMethod.GET, ADMIN_STATS_URL, token, null);

        try {
            // POST, not PATCH: endpoint 47 is a status transition with side effects beyond the named
            // column (every open session revoked, token_version bumped), so it is modelled as
            // `POST .../{id}/status` and there is no PATCH mapping for it. Calling `patched` here would
            // be answered by the dispatcher with 400 rather than performing the change.
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
            // Restored so a later test in another suite does not inherit a disabled account and read a
            // count it did not expect.
            ok(HttpMethod.POST, STATUS_URL.formatted(studentId), token, Map.of("status", "ACTIVE"));
        }

        JsonNode restored = ok(HttpMethod.GET, ADMIN_STATS_URL, token, null);
        assertThat(restored.get("activeStudents").asLong())
                .isEqualTo(before.get("activeStudents").asLong());
    }

    // ==================================================================
    //  61 — GET /api/v1/admin/stats/top-categories
    // ==================================================================

    @Test
    @DisplayName("UC-23: every ranking row carries exactly the documented fields")
    void rankingRowsCarryExactlyTheDocumentedFields() throws Exception {
        JsonNode ranking = ok(HttpMethod.GET, ADMIN_STATS_TOP_CATEGORIES_URL, adminToken(), null);

        assertThat(ranking.isArray()).isTrue();
        assertThat(ranking).isNotEmpty();
        for (JsonNode row : ranking) {
            assertThat(fieldNamesOf(row)).containsExactlyInAnyOrderElementsOf(DOCUMENTED_RANKING_FIELDS);
        }
        // `distinctUsers` is a count and `categoryName` is the category's - neither identifies a
        // student. A row that gained one would be the exposure this module's design rules out.
        assertThat(allKeysIn(ranking)).doesNotContain("userId", "user_id", "email", "fullName",
                "studentId", "student_id");
    }

    @Test
    @DisplayName("UC-23: a category nobody has used still appears, with zeroes")
    void anUnusedCategoryStillAppears() throws Exception {
        // The view is a LEFT JOIN from `categories` for the express purpose of including unused rows,
        // and the DAO adds no LIMIT. So a freshly created default category - which cannot have any
        // transaction - must be present with a zero count. Truncating the list or inner-joining would
        // turn "this category is unused" into "this category does not exist", which is the opposite of
        // what an administrator reading a usage report needs.
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

        // And the zero above is a zero among non-zero rows, not a uniformly empty report - which is
        // what makes it evidence that the row was included rather than that the whole list is empty.
        assertThat(ranking).anyMatch(ranked -> ranked.get("txnCount").asLong() > 0);
    }

    @Test
    @DisplayName("UC-23: a student's own category is told apart from a shared one")
    void apersonalCategoryIsToldApartFromASharedOne() throws Exception {
        // `scope` is the field that makes the ranking readable: two categories can share a name and
        // mean different things, and "Entertainment, 412 transactions" is a different fact depending
        // on whether it is the shared default or one student's private category.
        String studentToken = studentToken();
        String personalName = uniqueCategoryName();

        JsonNode personal = created(PERSONAL_CATEGORIES_URL, studentToken,
                Map.of("name", personalName, "type", "EXPENSE"));
        Long personalId = personal.get("id").asLong();

        // One transaction, so the row has a distinct user as well as a count.
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

        // Both scopes occur in one response, so the value is a distinction the view computes rather
        // than a constant this test could match either way round.
        assertThat(scopesIn(ranking)).contains("DEFAULT", "PERSONAL");
    }

    @Test
    @DisplayName("UC-23: a used category's count and total match the transactions behind it")
    void aUsedCategoryMatchesTheTransactionsBehindIt() throws Exception {
        // Cross-checked against the tables rather than against the row's own arithmetic: the ranking is
        // the view's answer, and this file's job is to show the endpoint reports that answer.
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

        // The order is the DAO's addition, not the view's: `v_admin_top_categories` defines none, so
        // without a total order two identical calls could return different sequences and a client that
        // diffed them would see movement that never happened. Compared as the full triple so a tie on
        // the count is checked against the total and a tie on both against the id.
        List<RankingKey> keys = rankingKeysOf(ranking);
        assertThat(keys)
                .as("txnCount DESC, totalAmount DESC, categoryId ASC")
                .isSortedAccordingTo(RankingKey.RANKING_ORDER);

        // Ordered by use, not arbitrary: the most-used category is not last.
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
        // No `LIMIT` and no scope filter: the row count is the whole `categories` table. A ranking that
        // showed only the top few, or only the shared categories, would answer a different question
        // than UC-23's usage report.
        JsonNode ranking = ok(HttpMethod.GET, ADMIN_STATS_TOP_CATEGORIES_URL, adminToken(), null);

        // The size is compared as a long rather than through `hasSize`, which takes an `int` - the
        // table is small, but a count read from the database should not have to be narrowed to be
        // compared.
        assertThat((long) categoryIdsIn(ranking).size()).isEqualTo(
                longValueFrom("SELECT COUNT(*) FROM categories"));
        assertThat(categoryIdsIn(ranking)).doesNotHaveDuplicates();
    }

    // ==================================================================
    //  Helpers
    // ==================================================================

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

    /**
     * One row's position in the ranking, so the order can be asserted as a property of the sequence.
     *
     * <p>A record rather than comparing the raw nodes, because the order reverses two fields and
     * ascends a third: a comparator written inline over JSON would hide that in a lambda, and the
     * reversal is the whole content of the rule.
     */
    private record RankingKey(long txnCount, BigDecimal totalAmount, long categoryId) {

        /** The DAO's stated order: most-used first, then largest total, then id ascending. */
        private static final Comparator<RankingKey> RANKING_ORDER =
                Comparator.comparingLong(RankingKey::txnCount).reversed()
                        .thenComparing(Comparator.comparing(RankingKey::totalAmount).reversed())
                        .thenComparingLong(RankingKey::categoryId);
    }
}
