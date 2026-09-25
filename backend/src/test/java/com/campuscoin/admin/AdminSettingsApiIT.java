package com.campuscoin.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Endpoints 58–59: the business thresholds (UC-23, VĐ-05).
 *
 * <p><b>This is the one module 11 file that touches state other suites read.</b> Every other suite
 * treats {@code system_settings} as fixed: {@code TipsRuleCoverageIT} says so in as many words, and
 * {@code DashboardApiIT} reads {@code tips.max_dashboard} to derive its own bound from it. So a test
 * here that leaves a threshold moved does not fail in this file - it fails somewhere else, in a class
 * that never mentions thresholds, and the failure looks like a defect in that module. The
 * {@link #changingAndRestoring} wrapper is the answer: it captures the stored value before the call and
 * writes it back in a {@code finally}, so an assertion that throws mid-test still restores. It writes
 * directly to the table because the point is to leave the row as it was found, not to exercise the
 * endpoint a second time.
 *
 * <p><b>{@code adjustable} is a promise, and it is tested as one in both directions.</b> The list says
 * which rows this API may change; the PATCH either changes exactly those or is refused for exactly the
 * rest. A parameterised test drives all six adjustable keys to success and another drives all ten
 * read-only keys to {@code 409 THRESHOLD_NOT_ADJUSTABLE}, from the same list the {@code GET} publishes -
 * so a row advertised as changeable cannot then be refused, or the reverse.
 *
 * <p><b>The two prefixes that look like module 12 are not.</b> {@code insight.spike_threshold_pct} and
 * {@code insight.spike_baseline_months} are adjustable, and the adjustable-keys test changes both. The
 * prefix suggests UC-17's monthly insights, which is locked; what these keys actually tune is BR-15's
 * category-spike detection, read by {@code sp_generate_tips} and joined by
 * {@code v_category_spend_trend} - behaviour that shipped in module 9. Module 12's own surfaces have no
 * key here. The test is where that reading is pinned, so a later change that locked them would fail
 * here and be explained by this comment.
 */
class AdminSettingsApiIT extends AbstractAdminApiIT {

    private static final String SETTING_URL = ADMIN_SETTINGS_URL + "/%s";

    /**
     * The response's fields, as a literal.
     *
     * <p>Five. {@code updatedBy} and {@code updatedAt} are absent deliberately: the audit trail records
     * who changed a threshold and when, and its {@code SETTING_CHANGED} rows carry the previous value
     * too, which a per-row copy could not.
     */
    private static final List<String> DOCUMENTED_FIELDS =
            List.of("key", "value", "valueType", "description", "adjustable");

    /**
     * The six keys {@code sp_admin_set_threshold} permits, each with a value it accepts.
     *
     * <p>Split from {@link #readOnlyKeys()} rather than derived from one list by subtraction, so that a
     * seventh key added to the procedure without a decision here fails the exact-set assertion on the
     * list test instead of quietly joining whichever half the derivation put it in.
     */
    private static final List<String> ADJUSTABLE_KEYS = List.of(
            "budget.near_threshold_pct",
            "budget.exceeded_threshold_pct",
            "insight.spike_threshold_pct",
            "insight.spike_baseline_months",
            "tips.max_dashboard",
            "auth.reset_token_ttl_minutes");

    /** The ten rows the API lists and refuses, with the reason each is read-only. */
    private static final List<String> READ_ONLY_KEYS = List.of(
            "app.currency",                 // set for the deployment
            "app.currency_symbol",
            "app.timezone",
            "app.week_start",
            "auth.session_ttl_minutes",     // authentication policy, read by Java
            "auth.max_login_attempts",
            "anomaly.duplicate_window_days", // UC-24, a capability this build does not have
            "anomaly.unusual_multiplier",
            "ai.enabled",                    // UC-08 / UC-17
            "ai.send_aggregates_only");

    /** Every seeded row, so a new one has to be added here as a decision. */
    private static final Set<String> ALL_SEEDED_KEYS = seededKeys();

    private static final String NON_ADJUSTABLE_KEY = "app.currency";
    private static final String WHOLE_NUMBER_KEY = "insight.spike_baseline_months";

    // ==================================================================
    //  58 — GET /api/v1/admin/settings
    // ==================================================================

    @Test
    @DisplayName("UC-23: the list is every seeded row, exactly, in key order")
    void theListIsEverySeededRowInKeyOrder() throws Exception {
        JsonNode settings = ok(HttpMethod.GET, ADMIN_SETTINGS_URL, adminToken(), null);

        assertThat(settings.isArray()).isTrue();
        assertThat(keysIn(settings)).containsExactlyInAnyOrderElementsOf(ALL_SEEDED_KEYS);
        // Ordered by key, so app.*, anomaly.*, auth.*, budget.*, insight.*, tips.* group together. The
        // order is imposed by the query rather than inherited: the table's return order is not part of
        // any contract, and a screen whose rows reshuffle between two identical calls is unusable.
        assertThat(keysIn(settings)).isSorted();
    }

    @Test
    @DisplayName("UC-23: the list publishes exactly the documented fields")
    void theListPublishesExactlyTheDocumentedFields() throws Exception {
        JsonNode settings = ok(HttpMethod.GET, ADMIN_SETTINGS_URL, adminToken(), null);

        for (JsonNode setting : settings) {
            assertThat(fieldNamesOf(setting))
                    .containsExactlyInAnyOrderElementsOf(DOCUMENTED_FIELDS);
        }
        // The audit columns are not published, and the scan is over the raw JSON rather than the
        // mapped record, so a nested object could not hide one either.
        assertThat(allKeysIn(settings)).doesNotContain("updatedBy", "updatedAt", "updated_by",
                "updated_at");
    }

    @Test
    @DisplayName("UC-23/VĐ-05: exactly the six keys are advertised as adjustable")
    void exactlyTheSixKeysAreAdvertisedAsAdjustable() throws Exception {
        JsonNode settings = ok(HttpMethod.GET, ADMIN_SETTINGS_URL, adminToken(), null);

        List<String> adjustable = new ArrayList<>();
        for (JsonNode setting : settings) {
            if (setting.get("adjustable").asBoolean()) {
                adjustable.add(setting.get("key").asText());
            }
        }

        // An exact set, not a count: a count would pass if one key were swapped for another, and the
        // six are the whole point of the flag.
        assertThat(adjustable).containsExactlyInAnyOrderElementsOf(ADJUSTABLE_KEYS);

        // Every row carries the flag as a real boolean, so nothing is nullable and a client need not
        // treat absent as false.
        for (JsonNode setting : settings) {
            assertThat(setting.get("adjustable").isBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("UC-23: a read-only row still shows its value, so the list is informative")
    void readOnlyRowsStillShowTheirValues() throws Exception {
        // The reason the ten are listed rather than hidden: the currency a deployment runs in and the
        // session lifetime in force are facts about the system being administered, and an
        // administrator who cannot see them cannot answer "what is this system actually doing".
        JsonNode settings = ok(HttpMethod.GET, ADMIN_SETTINGS_URL, adminToken(), null);

        JsonNode currency = settingWithKey(settings, NON_ADJUSTABLE_KEY);
        assertThat(currency).isNotNull();
        assertThat(currency.get("value").asText()).isEqualTo("USD");
        assertThat(currency.get("valueType").asText()).isEqualTo("STRING");
        assertThat(currency.get("adjustable").asBoolean()).isFalse();
    }

    // ==================================================================
    //  59 — PATCH /api/v1/admin/settings/{key}
    // ==================================================================

    @ParameterizedTest(name = "{0} accepts a new value and records it")
    @MethodSource("adjustableKeyValues")
    @DisplayName("VĐ-05: each of the six adjustable keys can be changed")
    void eachAdjustableKeyCanBeChanged(String key, String newValue) throws Exception {
        changingAndRestoring(key, () -> {
            JsonNode response = patched(SETTING_URL.formatted(key), adminToken(),
                    java.util.Map.of("value", newValue));

            assertThat(response.get("key").asText()).isEqualTo(key);
            assertThat(response.get("value").asText())
                    .as("the response carries the value just stored")
                    .isEqualTo(newValue);
            assertThat(response.get("adjustable").asBoolean()).isTrue();

            // The stored row, read directly - the procedure's UPDATE, not the response, is what the
            // rest of the system reads.
            assertThat(stringValueFrom(
                    "SELECT setting_value FROM system_settings WHERE setting_key = ?", key))
                    .isEqualTo(newValue);

            // The audit row, which is the only record of the previous value. `target_id` is NULL
            // because the target is named by its key rather than by a row id: `system_settings` is
            // keyed on a string, and `admin_audit_log.target_id` is a number.
            Long auditId = latestSettingAuditFor(key);
            assertThat(auditActionOf(auditId)).isEqualTo("SETTING_CHANGED");
            assertThat(auditActorOf(auditId)).isEqualTo(seededAdminId());
            assertThat(longValueFrom("SELECT COUNT(*) FROM admin_audit_log "
                    + "WHERE id = ? AND target_id IS NULL", auditId)).isEqualTo(1);
            assertThat(auditDetailFieldOf(auditId, "newValue")).isEqualTo(newValue);
            assertThat(auditDetailFieldOf(auditId, "oldValue"))
                    .as("the audit row is what makes a threshold change reversible")
                    .isNotBlank();
        });
    }

    @ParameterizedTest(name = "{0} is refused as not adjustable")
    @MethodSource("readOnlyKeys")
    @DisplayName("VĐ-05: every other seeded key is refused, and its value does not move")
    void everyReadOnlyKeyIsRefused(String key) throws Exception {
        String before = stringValueFrom(
                "SELECT setting_value FROM system_settings WHERE setting_key = ?", key);

        JsonNode error = refused(HttpMethod.PATCH, SETTING_URL.formatted(key), adminToken(),
                java.util.Map.of("value", "999"), HttpStatus.CONFLICT, "THRESHOLD_NOT_ADJUSTABLE");

        assertThat(error.get("message").asText()).contains(key);
        assertThat(stringValueFrom(
                "SELECT setting_value FROM system_settings WHERE setting_key = ?", key))
                .as("a refused change must not have written anything")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("UC-23: a key that exists nowhere is a 404, not a 409")
    void anUnknownKeyIsNotFound() throws Exception {
        // Two different facts, two answers: this key cannot be changed because it is read-only (409,
        // the row is there and the caller can see it in the list), versus this key does not exist at
        // all (404). The service checks existence first, which is the order that makes them
        // distinguishable - the procedure answers both with the same 45000.
        JsonNode error = refused(HttpMethod.PATCH, SETTING_URL.formatted("no.such.setting"),
                adminToken(), java.util.Map.of("value", "1"), HttpStatus.NOT_FOUND, "NOT_FOUND");

        assertThat(error.get("message").asText()).isEqualTo("Setting not found.");
    }

    @Test
    @DisplayName("VĐ-05: a value that is not a number is a field error naming `value`")
    void aNonNumericValueIsAFieldError() throws Exception {
        changingAndRestoring("budget.near_threshold_pct", () -> {
            JsonNode error = refused(HttpMethod.PATCH,
                    SETTING_URL.formatted("budget.near_threshold_pct"), adminToken(),
                    java.util.Map.of("value", "ninety"),
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");

            assertThat(fieldNamesIn(error)).containsExactly("value");
            // The procedure would refuse this too, with SQLSTATE 45000 and the prose "Threshold must be
            // a positive number" - the same signal it uses for every one of its refusals, including
            // sp_require_admin's. Validating here is what turns that into a field error the client can
            // render, and the wording is checked so the two cannot drift into agreement on nothing.
            assertThat(error.get("fieldErrors").get(0).get("message").asText())
                    .contains("positive number");
        });
    }

    @Test
    @DisplayName("VĐ-05: a number that is not positive is refused the same way")
    void aNonPositiveNumberIsAFieldError() throws Exception {
        changingAndRestoring("budget.exceeded_threshold_pct", () -> {
            for (String notPositive : List.of("0", "-5", "0.0")) {
                JsonNode error = refused(HttpMethod.PATCH,
                        SETTING_URL.formatted("budget.exceeded_threshold_pct"), adminToken(),
                        java.util.Map.of("value", notPositive),
                        HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
                assertThat(fieldNamesIn(error)).containsExactly("value");
            }
            // And the same key accepts a positive decimal, so the refusals above are about the value
            // and not about the key or the request shape.
            patched(SETTING_URL.formatted("budget.exceeded_threshold_pct"), adminToken(),
                    java.util.Map.of("value", "101.5"));
        });
    }

    @Test
    @DisplayName("BR-15: the baseline month count takes a whole number from 1 to 12 and nothing else")
    void theBaselineMonthCountIsBoundedAndWhole() throws Exception {
        // The one key with a second rule. Each refusal is paired against the ends of the range being
        // accepted, so a check that refused every value - or none - could not pass both halves.
        changingAndRestoring(WHOLE_NUMBER_KEY, () -> {
            for (String refusedValue : List.of("0", "13", "2.5", "-1", "half")) {
                JsonNode error = refused(HttpMethod.PATCH, SETTING_URL.formatted(WHOLE_NUMBER_KEY),
                        adminToken(), java.util.Map.of("value", refusedValue),
                        HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
                assertThat(fieldNamesIn(error)).containsExactly("value");
                assertThat(error.get("fieldErrors").get(0).get("message").asText())
                        .as("the message states the range, not merely that the value was wrong")
                        .contains("1 to 12");
            }

            for (String accepted : List.of("1", "12", "4")) {
                JsonNode response = patched(SETTING_URL.formatted(WHOLE_NUMBER_KEY), adminToken(),
                        java.util.Map.of("value", accepted));
                assertThat(response.get("value").asText()).isEqualTo(accepted);
            }
        });
    }

    @Test
    @DisplayName("UC-23: a missing value is a field error, not an empty string stored")
    void aMissingValueIsAFieldError() throws Exception {
        changingAndRestoring("tips.max_dashboard", () -> {
            JsonNode absent = refused(HttpMethod.PATCH, SETTING_URL.formatted("tips.max_dashboard"),
                    adminToken(), java.util.Map.of(), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
            assertThat(fieldNamesIn(absent)).containsExactly("value");

            JsonNode blank = refused(HttpMethod.PATCH, SETTING_URL.formatted("tips.max_dashboard"),
                    adminToken(), java.util.Map.of("value", "   "),
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
            assertThat(fieldNamesIn(blank)).containsExactly("value");
        });
    }

    @Test
    @DisplayName("UC-23: a refusal names the key and leaves the stored value where it was")
    void aRefusalLeavesTheStoredValueAlone() throws Exception {
        // The positive-and-negative pair for the 409: the same value ("999") is refused on the
        // read-only key above and accepted on an adjustable one here. Nothing about the request is
        // wrong in either case - only the key decides.
        changingAndRestoring("auth.reset_token_ttl_minutes", () -> {
            String before = stringValueFrom("SELECT setting_value FROM system_settings "
                    + "WHERE setting_key = ?", "auth.reset_token_ttl_minutes");
            refused(HttpMethod.PATCH, SETTING_URL.formatted(NON_ADJUSTABLE_KEY), adminToken(),
                    java.util.Map.of("value", "999"), HttpStatus.CONFLICT, "THRESHOLD_NOT_ADJUSTABLE");
            assertThat(stringValueFrom("SELECT setting_value FROM system_settings "
                    + "WHERE setting_key = ?", "auth.reset_token_ttl_minutes")).isEqualTo(before);

            patched(SETTING_URL.formatted("auth.reset_token_ttl_minutes"), adminToken(),
                    java.util.Map.of("value", "999"));
            assertThat(stringValueFrom("SELECT setting_value FROM system_settings "
                    + "WHERE setting_key = ?", "auth.reset_token_ttl_minutes")).isEqualTo("999");
        });
    }

    @Test
    @DisplayName("UC-23: the value is stored as sent, including its decimal places")
    void theValueIsStoredAsSent() throws Exception {
        // `setting_value` is VARCHAR(255) and is read as text by everything that consumes it, so the
        // API must not normalise it on the way through. A service that parsed the number and
        // re-rendered it would turn "090.50" into "90.5" here - harmless-looking and a different
        // stored value, which is a change to a row this endpoint was only asked to set to one string.
        changingAndRestoring("insight.spike_threshold_pct", () -> {
            JsonNode response = patched(SETTING_URL.formatted("insight.spike_threshold_pct"),
                    adminToken(), java.util.Map.of("value", "090.50"));

            assertThat(response.get("value").asText()).isEqualTo("090.50");
            assertThat(stringValueFrom("SELECT setting_value FROM system_settings "
                    + "WHERE setting_key = ?", "insight.spike_threshold_pct"))
                    .isEqualTo("090.50");
        });
    }

    @Test
    @DisplayName("UC-23: the value type is not changed by an update")
    void valueTypeIsUnchangedByAnUpdate() throws Exception {
        // The five fields other than `value` describe the row rather than the write, and an update
        // touches only `setting_value` and `updated_by`. A response whose `valueType` tracked the
        // request would let a caller change how a row is interpreted by writing to it.
        changingAndRestoring("tips.max_dashboard", () -> {
            String typeBefore = stringValueFrom("SELECT value_type FROM system_settings "
                    + "WHERE setting_key = ?", "tips.max_dashboard");

            JsonNode response = patched(SETTING_URL.formatted("tips.max_dashboard"), adminToken(),
                    java.util.Map.of("value", "7"));

            assertThat(response.get("valueType").asText()).isEqualTo(typeBefore);
            assertThat(response.get("valueType").asText()).isEqualTo("INT");
            assertThat(response.get("description").asText())
                    .as("the description is the row's, not the request's")
                    .isNotBlank();
        });
    }

    // ==================================================================
    //  Sources
    // ==================================================================

    private static Stream<Arguments> adjustableKeyValues() {
        return Stream.of(
                arguments("budget.near_threshold_pct", "85"),
                arguments("budget.exceeded_threshold_pct", "110"),
                arguments("insight.spike_threshold_pct", "42"),
                arguments("insight.spike_baseline_months", "4"),
                arguments("tips.max_dashboard", "5"),
                arguments("auth.reset_token_ttl_minutes", "45"));
    }

    private static Stream<String> readOnlyKeys() {
        return READ_ONLY_KEYS.stream();
    }

    private static Set<String> seededKeys() {
        return java.util.stream.Stream.concat(ADJUSTABLE_KEYS.stream(), READ_ONLY_KEYS.stream())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    // ==================================================================
    //  Helpers
    // ==================================================================

    /**
     * Runs a request against a threshold and puts the stored value back afterwards.
     *
     * <p><b>Restored in a {@code finally}, because the test that needs it most is the one that
     * fails.</b> These six keys are read by other suites; a change left behind would surface as an
     * unexplained failure in {@code TipsRuleCoverageIT} or {@code DashboardApiIT}, which is worse than
     * a failure here. The write goes straight to the table rather than back through the endpoint
     * because the object is to leave the row as it was found - going through the procedure again would
     * add a second audit row for a change no administrator made.
     */
    private void changingAndRestoring(String key, ApiCall call) throws Exception {
        String original = stringValueFrom(
                "SELECT setting_value FROM system_settings WHERE setting_key = ?", key);
        try {
            call.run();
        } finally {
            runInDatabase("UPDATE system_settings SET setting_value = ? WHERE setting_key = ?",
                    original, key);
            assertThat(stringValueFrom(
                    "SELECT setting_value FROM system_settings WHERE setting_key = ?", key))
                    .as("the threshold must be back where it started")
                    .isEqualTo(original);
        }
    }

    /** A request body that may throw, so {@link #changingAndRestoring} can take a lambda. */
    @FunctionalInterface
    private interface ApiCall {
        void run() throws Exception;
    }

    /**
     * The newest {@code SETTING_CHANGED} row naming this key.
     *
     * <p>Keyed through the audit {@code detail} JSON rather than by {@code target_id}, which is NULL for
     * every one of these rows: the target is a string-keyed setting and {@code target_id} is a number.
     * Reading the key out of the detail is therefore the only way to tell two setting changes apart, and
     * it is exact - the alternative, taking the newest {@code SETTING_CHANGED} row overall, would depend
     * on no other test having changed a setting in between.
     */
    private Long latestSettingAuditFor(String key) throws Exception {
        return longValueFrom(
                "SELECT id FROM admin_audit_log WHERE action = 'SETTING_CHANGED' "
                        + " AND JSON_UNQUOTE(JSON_EXTRACT(detail, '$.key')) = ? "
                        + " ORDER BY id DESC LIMIT 1", key);
    }

    private static List<String> keysIn(JsonNode settings) {
        List<String> keys = new ArrayList<>();
        settings.forEach(setting -> keys.add(setting.get("key").asText()));
        return keys;
    }

    private static JsonNode settingWithKey(JsonNode settings, String key) {
        for (JsonNode setting : settings) {
            if (key.equals(setting.get("key").asText())) {
                return setting;
            }
        }
        return null;
    }
}
