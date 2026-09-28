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

class AdminSettingsApiIT extends AbstractAdminApiIT {

    private static final String SETTING_URL = ADMIN_SETTINGS_URL + "/%s";

    private static final List<String> DOCUMENTED_FIELDS =
            List.of("key", "value", "valueType", "description", "adjustable");

    private static final List<String> ADJUSTABLE_KEYS = List.of(
            "budget.near_threshold_pct",
            "budget.exceeded_threshold_pct",
            "insight.spike_threshold_pct",
            "insight.spike_baseline_months",
            "tips.max_dashboard",
            "auth.reset_token_ttl_minutes");

    private static final List<String> READ_ONLY_KEYS = List.of(
            "app.currency",
            "app.currency_symbol",
            "app.timezone",
            "app.week_start",
            "auth.session_ttl_minutes",
            "auth.max_login_attempts",
            "anomaly.duplicate_window_days",
            "anomaly.unusual_multiplier",
            "ai.enabled",
            "ai.send_aggregates_only");

    private static final Set<String> ALL_SEEDED_KEYS = seededKeys();

    private static final String NON_ADJUSTABLE_KEY = "app.currency";
    private static final String WHOLE_NUMBER_KEY = "insight.spike_baseline_months";

    @Test
    @DisplayName("UC-23: the list is every seeded row, exactly, in key order")
    void theListIsEverySeededRowInKeyOrder() throws Exception {
        JsonNode settings = ok(HttpMethod.GET, ADMIN_SETTINGS_URL, adminToken(), null);

        assertThat(settings.isArray()).isTrue();
        assertThat(keysIn(settings)).containsExactlyInAnyOrderElementsOf(ALL_SEEDED_KEYS);

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

        assertThat(adjustable).containsExactlyInAnyOrderElementsOf(ADJUSTABLE_KEYS);

        for (JsonNode setting : settings) {
            assertThat(setting.get("adjustable").isBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("UC-23: a read-only row still shows its value, so the list is informative")
    void readOnlyRowsStillShowTheirValues() throws Exception {

        JsonNode settings = ok(HttpMethod.GET, ADMIN_SETTINGS_URL, adminToken(), null);

        JsonNode currency = settingWithKey(settings, NON_ADJUSTABLE_KEY);
        assertThat(currency).isNotNull();
        assertThat(currency.get("value").asText()).isEqualTo("USD");
        assertThat(currency.get("valueType").asText()).isEqualTo("STRING");
        assertThat(currency.get("adjustable").asBoolean()).isFalse();
    }

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

            assertThat(stringValueFrom(
                    "SELECT setting_value FROM system_settings WHERE setting_key = ?", key))
                    .isEqualTo(newValue);

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

            patched(SETTING_URL.formatted("budget.exceeded_threshold_pct"), adminToken(),
                    java.util.Map.of("value", "101.5"));
        });
    }

    @Test
    @DisplayName("BR-15: the baseline month count takes a whole number from 1 to 12 and nothing else")
    void theBaselineMonthCountIsBoundedAndWhole() throws Exception {

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

    @FunctionalInterface
    private interface ApiCall {
        void run() throws Exception;
    }

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
