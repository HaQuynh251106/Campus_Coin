package com.campuscoin.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;

class AdminTipTemplateApiIT extends AbstractAdminApiIT {

    private static final String TEMPLATE_ITEM_URL = ADMIN_TIP_TEMPLATES_URL + "/%d";

    private static final List<String> DOCUMENTED_FIELDS = List.of(
            "id", "code", "conditionType", "titleTemplate", "bodyTemplate", "defaultPriority",
            "isActive");

    @Test
    @DisplayName("UC-21: the list is every template, seeded ones included, in display order")
    void theListIncludesTheSeededTemplatesInDisplayOrder() throws Exception {
        JsonNode templates = ok(HttpMethod.GET, ADMIN_TIP_TEMPLATES_URL, adminToken(), null);

        assertThat(templates.isArray()).isTrue();

        assertThat(templates).hasSizeGreaterThanOrEqualTo(7);
        assertThat(codesIn(templates))
                .as("the rule-backed templates BR-15 names must all be present")
                .contains("OVER_BUDGET", "NEAR_BUDGET", "CATEGORY_SPIKE", "NO_BUDGET_SET");

        int previousPriority = Integer.MIN_VALUE;
        for (JsonNode template : templates) {
            int priority = template.get("defaultPriority").asInt();
            assertThat(priority).isGreaterThanOrEqualTo(previousPriority);
            previousPriority = priority;
        }
    }

    @Test
    @DisplayName("UC-21: the list publishes exactly the documented fields and never conditionParams")
    void theListPublishesExactlyTheDocumentedFields() throws Exception {
        JsonNode templates = ok(HttpMethod.GET, ADMIN_TIP_TEMPLATES_URL, adminToken(), null);

        for (JsonNode template : templates) {
            assertThat(fieldNamesOf(template))
                    .containsExactlyInAnyOrderElementsOf(DOCUMENTED_FIELDS);

            assertThat(fieldNamesOf(template)).doesNotContain("conditionParams", "condition_params");
        }

        assertThat(countOf("SELECT COUNT(*) FROM tip_templates WHERE condition_params IS NOT NULL"))
                .as("the payloads above must be rows that could have leaked a value")
                .isGreaterThanOrEqualTo(4);

        assertThat(allKeysIn(templates)).doesNotContain("conditionParams", "condition_params");
    }

    @Test
    @DisplayName("UC-21: two identical calls return the same order")
    void theListOrderIsStable() throws Exception {
        String token = adminToken();

        JsonNode first = ok(HttpMethod.GET, ADMIN_TIP_TEMPLATES_URL, token, null);
        JsonNode second = ok(HttpMethod.GET, ADMIN_TIP_TEMPLATES_URL, token, null);

        assertThat(idsOf(first)).isEqualTo(idsOf(second));

        assertThat(orderingKeysOf(first)).isSorted();
    }

    @Test
    @DisplayName("UC-21 B3: a created template comes back with the code the database stored")
    void creatingReturnsTheRowThatWasCreated() throws Exception {

        String code = randomTipTemplateCode();

        JsonNode response = created(ADMIN_TIP_TEMPLATES_URL, adminToken(), Map.of(
                "code", code,
                "conditionType", "SAVINGS_GOAL_AT_RISK",
                "titleTemplate", "Your savings goal is at risk",
                "bodyTemplate", "You are behind on this month's savings goal.",
                "defaultPriority", 30,
                "isActive", true));

        Long id = response.get("id").asLong();
        assertThat(response.get("code").asText()).isEqualTo(code);
        assertThat(response.get("conditionType").asText()).isEqualTo("SAVINGS_GOAL_AT_RISK");
        assertThat(response.get("defaultPriority").asInt()).isEqualTo(30);
        assertThat(response.get("isActive").asBoolean()).isTrue();

        assertThat(stringValueFrom("SELECT code FROM tip_templates WHERE id = ?", id))
                .as("the response's id is the row that holds the code that was sent")
                .isEqualTo(code);

        Long auditId = latestAuditIdFor("TIP_TEMPLATE_SAVED", "tip_templates", id);
        assertThat(auditActorOf(auditId)).isEqualTo(seededAdminId());
        assertThat(auditDetailFieldOf(auditId, "code")).isEqualTo(code);
    }

    @Test
    @DisplayName("UC-21 B3: a lower-case code is stored upper-case, so sp_generate_tips can match it")
    void aLowerCaseCodeIsNormalisedToUpperCase() throws Exception {

        String mixedCase = "Suite_" + UUID.randomUUID().toString().substring(0, 8) + "_Mixed";

        JsonNode response = created(ADMIN_TIP_TEMPLATES_URL, adminToken(), Map.of(
                "code", mixedCase,
                "titleTemplate", "A template with a mixed-case code",
                "bodyTemplate", "The code is stored upper-case.",
                "defaultPriority", 200));

        assertThat(response.get("code").asText())
                .isEqualTo(mixedCase.toUpperCase(Locale.ROOT));
        assertThat(stringValueFrom("SELECT code FROM tip_templates WHERE id = ?",
                response.get("id").asLong()))
                .isEqualTo(mixedCase.toUpperCase(Locale.ROOT));
    }

    @Test
    @DisplayName("UC-21 B3: an omitted conditionType falls back rather than leaving the column null")
    void anOmittedConditionTypeFallsBack() throws Exception {

        JsonNode response = created(ADMIN_TIP_TEMPLATES_URL, adminToken(), Map.of(
                "code", randomTipTemplateCode(),
                "titleTemplate", "A template with no condition type",
                "bodyTemplate", "It falls back to GENERIC.",
                "defaultPriority", 210));

        assertThat(response.get("conditionType").asText()).isEqualTo("GENERIC");
        assertThat(response.get("isActive").asBoolean())
                .as("an omitted isActive is active, which is what a new template is for")
                .isTrue();
    }

    @Test
    @DisplayName("UC-21 B3: a duplicate code is refused as taken, in both cases")
    void aDuplicateCodeIsRefusedAsTaken() throws Exception {

        String token = adminToken();
        String code = randomTipTemplateCode();
        created(ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", code,
                "titleTemplate", "The original",
                "bodyTemplate", "The template that holds this code.",
                "defaultPriority", 220));

        JsonNode exactDuplicate = refused(HttpMethod.POST, ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", code,
                "titleTemplate", "A copy",
                "bodyTemplate", "A second template claiming the same code.",
                "defaultPriority", 221), HttpStatus.CONFLICT, "TIP_TEMPLATE_CODE_TAKEN");
        assertThat(exactDuplicate.get("message").asText()).contains(code);

        refused(HttpMethod.POST, ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", code.toLowerCase(Locale.ROOT),
                "titleTemplate", "A copy in lower case",
                "bodyTemplate", "A third template claiming the same code.",
                "defaultPriority", 222), HttpStatus.CONFLICT, "TIP_TEMPLATE_CODE_TAKEN");

        assertThat(countOf("SELECT COUNT(*) FROM tip_templates WHERE UPPER(code) = ?", code))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("UC-21 B3: a code that is not [A-Za-z0-9_] is a field error, matching VARCHAR(50)")
    void anInvalidCodeIsAFieldError() throws Exception {

        String token = adminToken();

        JsonNode withSpace = refused(HttpMethod.POST, ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", "SUITE CODE",
                "titleTemplate", "A title",
                "bodyTemplate", "A body",
                "defaultPriority", 230), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNamesIn(withSpace)).containsExactly("code");

        JsonNode tooLong = refused(HttpMethod.POST, ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", "C".repeat(51),
                "titleTemplate", "A title",
                "bodyTemplate", "A body",
                "defaultPriority", 231), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNamesIn(tooLong)).containsExactly("code");
    }

    @Test
    @DisplayName("UC-21 B3: the required text fields and the priority are all checked")
    void theOtherRequiredFieldsAreChecked() throws Exception {
        String token = adminToken();

        JsonNode noTitle = refused(HttpMethod.POST, ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", randomTipTemplateCode(),
                "bodyTemplate", "A body with no title template.",
                "defaultPriority", 240), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNamesIn(noTitle)).contains("titleTemplate");

        JsonNode noBody = refused(HttpMethod.POST, ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", randomTipTemplateCode(),
                "titleTemplate", "A title with no body template.",
                "defaultPriority", 241), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNamesIn(noBody)).contains("bodyTemplate");

        JsonNode noPriority = refused(HttpMethod.POST, ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", randomTipTemplateCode(),
                "titleTemplate", "A title",
                "bodyTemplate", "A body"), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNamesIn(noPriority)).contains("defaultPriority");
    }

    @Test
    @DisplayName("UC-21 B3: an unknown condition type is a field error naming the column")
    void anUnknownConditionTypeIsAFieldError() throws Exception {
        JsonNode error = refused(HttpMethod.POST, ADMIN_TIP_TEMPLATES_URL, adminToken(), Map.of(
                "code", randomTipTemplateCode(),
                "conditionType", "NOT_A_RULE",
                "titleTemplate", "A title",
                "bodyTemplate", "A body",
                "defaultPriority", 250), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");

        assertThat(fieldNamesIn(error)).contains("conditionType");
    }

    @Test
    @DisplayName("UC-21 B4: an update changes the editable fields and leaves the code alone")
    void updatingChangesTheEditableFields() throws Exception {
        String token = adminToken();
        String code = randomTipTemplateCode();
        Long id = created(ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", code,
                "conditionType", "GENERIC",
                "titleTemplate", "Before the edit",
                "bodyTemplate", "The body before the edit.",
                "defaultPriority", 260)).get("id").asLong();

        JsonNode response = patched(TEMPLATE_ITEM_URL.formatted(id), token, Map.of(
                "titleTemplate", "After the edit",
                "bodyTemplate", "The body after the edit.",
                "conditionType", "NO_BUDGET_SET",
                "defaultPriority", 261,
                "isActive", false));

        assertThat(response.get("id").asLong()).isEqualTo(id);
        assertThat(response.get("code").asText()).isEqualTo(code);
        assertThat(response.get("titleTemplate").asText()).isEqualTo("After the edit");
        assertThat(response.get("bodyTemplate").asText()).isEqualTo("The body after the edit.");
        assertThat(response.get("conditionType").asText()).isEqualTo("NO_BUDGET_SET");
        assertThat(response.get("defaultPriority").asInt()).isEqualTo(261);
        assertThat(response.get("isActive").asBoolean()).isFalse();

        Long auditId = latestAuditIdFor("TIP_TEMPLATE_SAVED", "tip_templates", id);
        assertThat(auditDetailFieldOf(auditId, "code")).isEqualTo(code);
        assertThat(auditDetailFieldOf(auditId, "isActive")).isEqualTo("0");
    }

    @Test
    @DisplayName("UC-21 B4: sending the stored code back is accepted, so a full object round-trips")
    void sendingTheStoredCodeBackIsAccepted() throws Exception {

        String token = adminToken();
        String code = randomTipTemplateCode();
        Long id = created(ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", code,
                "titleTemplate", "A round-tripped template",
                "bodyTemplate", "Its code is sent back unchanged.",
                "defaultPriority", 270)).get("id").asLong();

        JsonNode response = patched(TEMPLATE_ITEM_URL.formatted(id), token, Map.of(
                "code", code,
                "titleTemplate", "A round-tripped template, edited",
                "defaultPriority", 271));

        assertThat(response.get("code").asText()).isEqualTo(code);
        assertThat(response.get("titleTemplate").asText()).isEqualTo("A round-tripped template, edited");
    }

    @Test
    @DisplayName("UC-21 B4: sending a different code is refused, and the stored code does not move")
    void aDifferentCodeIsRefusedAndTheStoredCodeIsUnchanged() throws Exception {

        String token = adminToken();
        String storedCode = randomTipTemplateCode();
        Long id = created(ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", storedCode,
                "titleTemplate", "The template whose code cannot change",
                "bodyTemplate", "Its code is its identity.",
                "defaultPriority", 280)).get("id").asLong();

        int auditsBefore = countOf("SELECT COUNT(*) FROM admin_audit_log WHERE target_id = ? "
                + "AND action = 'TIP_TEMPLATE_SAVED'", id);

        String requestedCode = randomTipTemplateCode();
        JsonNode error = refused(HttpMethod.PATCH, TEMPLATE_ITEM_URL.formatted(id), token, Map.of(
                "code", requestedCode,
                "titleTemplate", "An edit that must not be applied"),
                HttpStatus.CONFLICT, "TIP_TEMPLATE_CODE_IMMUTABLE");
        assertThat(error.get("message").asText()).contains(storedCode);

        assertThat(stringValueFrom("SELECT code FROM tip_templates WHERE id = ?", id))
                .isEqualTo(storedCode);

        assertThat(stringValueFrom("SELECT title_template FROM tip_templates WHERE id = ?", id))
                .isEqualTo("The template whose code cannot change");
        assertThat(countOf("SELECT COUNT(*) FROM admin_audit_log WHERE target_id = ? "
                + "AND action = 'TIP_TEMPLATE_SAVED'", id))
                .as("a refused edit must leave no audit row of its own")
                .isEqualTo(auditsBefore);
    }

    @Test
    @DisplayName("UC-21 B4: the code refusal is not avoided by a change of case")
    void aCaseChangedCodeIsAlsoRefused() throws Exception {

        String token = adminToken();
        String storedCode = randomTipTemplateCode();
        Long id = created(ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", storedCode,
                "titleTemplate", "Case matters not",
                "bodyTemplate", "The comparison normalises first.",
                "defaultPriority", 290)).get("id").asLong();

        JsonNode response = patched(TEMPLATE_ITEM_URL.formatted(id), token, Map.of(
                "code", storedCode.toLowerCase(Locale.ROOT),
                "titleTemplate", "Case normalised, so this is an ordinary edit"));

        assertThat(response.get("code").asText()).isEqualTo(storedCode);
        assertThat(response.get("titleTemplate").asText())
                .isEqualTo("Case normalised, so this is an ordinary edit");
    }

    @Test
    @DisplayName("UC-21 B4: an update naming no template is a 404")
    void anUnknownTemplateIsNotFound() throws Exception {
        JsonNode error = refused(HttpMethod.PATCH,
                TEMPLATE_ITEM_URL.formatted(noSuchIdIn("tip_templates")), adminToken(),
                Map.of("titleTemplate", "An edit to nothing"), HttpStatus.NOT_FOUND, "NOT_FOUND");

        assertThat(error.get("message").asText()).isEqualTo("Tip template not found.");
    }

    @Test
    @DisplayName("UC-21 B4: an empty edit is accepted and changes nothing")
    void anEmptyEditChangesNothing() throws Exception {

        String token = adminToken();
        Long id = created(ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", randomTipTemplateCode(),
                "conditionType", "CATEGORY_SPIKE",
                "titleTemplate", "Untouched",
                "bodyTemplate", "Untouched body.",
                "defaultPriority", 300)).get("id").asLong();

        JsonNode response = patched(TEMPLATE_ITEM_URL.formatted(id), token, Map.of());

        assertThat(response.get("titleTemplate").asText()).isEqualTo("Untouched");
        assertThat(response.get("bodyTemplate").asText()).isEqualTo("Untouched body.");
        assertThat(response.get("conditionType").asText()).isEqualTo("CATEGORY_SPIKE");
        assertThat(response.get("defaultPriority").asInt()).isEqualTo(300);
        assertThat(response.get("isActive").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("UC-21 B4: a switched-off template stays in the list, so it can be switched back on")
    void aRetiredTemplateStaysInTheList() throws Exception {

        String token = adminToken();
        String code = randomTipTemplateCode();
        Long id = created(ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", code,
                "titleTemplate", "Switched off",
                "bodyTemplate", "This template is retired by the suite.",
                "defaultPriority", 310)).get("id").asLong();

        patched(TEMPLATE_ITEM_URL.formatted(id), token, Map.of("isActive", false));

        JsonNode listed = templateWithId(ok(HttpMethod.GET, ADMIN_TIP_TEMPLATES_URL, token, null), id);
        assertThat(listed).as("a retired template must remain readable").isNotNull();
        assertThat(listed.get("isActive").asBoolean()).isFalse();
        assertThat(listed.get("code").asText()).isEqualTo(code);
    }

    private static List<String> codesIn(JsonNode templates) {
        List<String> codes = new ArrayList<>();
        templates.forEach(template -> codes.add(template.get("code").asText()));
        return codes;
    }

    private static List<String> orderingKeysOf(JsonNode templates) {
        List<String> keys = new ArrayList<>();
        templates.forEach(template -> keys.add(
                "%010d|%020d".formatted(
                        template.get("defaultPriority").asInt(),
                        template.get("id").asLong())));
        return keys;
    }

    private static JsonNode templateWithId(JsonNode templates, Long id) {
        for (JsonNode template : templates) {
            if (template.get("id").asLong() == id) {
                return template;
            }
        }
        return null;
    }
}
