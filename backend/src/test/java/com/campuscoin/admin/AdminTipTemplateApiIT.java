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

/**
 * Endpoints 55–57: the saving-tip templates (UC-21 B3, B4).
 *
 * <p><b>The code is a template's identity, and the interesting failure is the silent one.</b>
 * {@code sp_admin_upsert_tip_template}'s update branch does not touch {@code code}, so a PATCH
 * carrying a different one would succeed, change nothing, and report success - a client told "saved"
 * while the value it sent went nowhere. The service refuses that with 409
 * {@code TIP_TEMPLATE_CODE_IMMUTABLE} instead, and this suite pins both halves: the refusal, and the
 * fact that the stored code is what the audit row records rather than what the caller typed.
 *
 * <p><b>A duplicate code is a different 409, classified from a constraint name.</b> That is the part
 * of the module that could not be done by SQLSTATE alone: the procedures signal every one of their own
 * refusals with 45000, and {@code uk_tip_template_code} is a 23000 - the same state as a restricting
 * foreign key. {@code AdminWriteFailureTest} covers the classification directly; this file covers the
 * path, which is where the constraint name actually arrives from MySQL.
 *
 * <p><b>{@code condition_params} is not exposed.</b> The column exists and no procedure parameter
 * reaches it, so it can be neither read nor written here; the response omits it, and the list test
 * asserts that as a key absence rather than as a comment.
 */
class AdminTipTemplateApiIT extends AbstractAdminApiIT {

    private static final String TEMPLATE_ITEM_URL = ADMIN_TIP_TEMPLATES_URL + "/%d";

    /**
     * The fields a tip template response carries, as a literal.
     *
     * <p>Seven. Absent and deliberate: {@code conditionParams} (nothing in this build reads the
     * column, so publishing it would invite a client to depend on a value no code interprets),
     * {@code createdBy} and the timestamps (the audit trail is the record of who changed what).
     */
    private static final List<String> DOCUMENTED_FIELDS = List.of(
            "id", "code", "conditionType", "titleTemplate", "bodyTemplate", "defaultPriority",
            "isActive");

    // ==================================================================
    //  55 — GET /api/v1/admin/tip-templates
    // ==================================================================

    @Test
    @DisplayName("UC-21: the list is every template, seeded ones included, in display order")
    void theListIncludesTheSeededTemplatesInDisplayOrder() throws Exception {
        JsonNode templates = ok(HttpMethod.GET, ADMIN_TIP_TEMPLATES_URL, adminToken(), null);

        assertThat(templates.isArray()).isTrue();
        // Seven are seeded and nothing in this module deletes one, so the count can only grow as
        // earlier tests in the run leave their own templates behind.
        assertThat(templates).hasSizeGreaterThanOrEqualTo(7);
        assertThat(codesIn(templates))
                .as("the rule-backed templates BR-15 names must all be present")
                .contains("OVER_BUDGET", "NEAR_BUDGET", "CATEGORY_SPIKE", "NO_BUDGET_SET");

        // `default_priority` is the order the tip generator ranks by, so the administration screen
        // shows the templates in the order they would be chosen. A list in insertion order would
        // make the screen's ordering a different question from the generator's.
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
            // `condition_params` is a JSON column with no documented meaning and no reader in this
            // build, so it is not published under either spelling.
            assertThat(fieldNamesOf(template)).doesNotContain("conditionParams", "condition_params");
        }

        // The seeded templates do carry a condition_params - four of the seven, per db/05_seed.sql -
        // so the absence above is a decision about the response rather than a consequence of the
        // column being empty everywhere. Without this the assertion could pass on a database where
        // condition_params was null for every row, which is not the database this runs against.
        assertThat(countOf("SELECT COUNT(*) FROM tip_templates WHERE condition_params IS NOT NULL"))
                .as("the payloads above must be rows that could have leaked a value")
                .isGreaterThanOrEqualTo(4);
        // And no payload at any depth carries one, in case a nested object ever appears.
        assertThat(allKeysIn(templates)).doesNotContain("conditionParams", "condition_params");
    }

    @Test
    @DisplayName("UC-21: two identical calls return the same order")
    void theListOrderIsStable() throws Exception {
        String token = adminToken();

        JsonNode first = ok(HttpMethod.GET, ADMIN_TIP_TEMPLATES_URL, token, null);
        JsonNode second = ok(HttpMethod.GET, ADMIN_TIP_TEMPLATES_URL, token, null);

        assertThat(idsOf(first)).isEqualTo(idsOf(second));
        // The order is (default_priority, id), so the ids themselves are *not* ascending - a template
        // created by an earlier test has a high id and sits between two seeded rows. The pair is what
        // has to be monotonic, and it is the tie-break that makes two identical calls comparable.
        assertThat(orderingKeysOf(first)).isSorted();
    }

    // ==================================================================
    //  56 — POST /api/v1/admin/tip-templates
    // ==================================================================

    @Test
    @DisplayName("UC-21 B3: a created template comes back with the code the database stored")
    void creatingReturnsTheRowThatWasCreated() throws Exception {
        // The read-back trap on this path is *not* the LAST_INSERT_ID one - that would be caught by
        // an id check alone. It is that the read-back is keyed on the code, and the code the service
        // looks up is the normalised one; a service that stored `my_code` and looked up what it
        // received would fail here, and the audit row's `target_id` is the independent witness that
        // the row returned is the row written.
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
        // `sp_generate_tips` joins on literal codes such as 'OVER_BUDGET', so a stored 'over_budget'
        // would be a template that never fires. The normalisation is the module's job because the
        // procedure has no reason to do it: it receives whatever its caller sends.
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
        // `condition_type` is NOT NULL with values tied to BR-15's rules, so a template created
        // without one has to land on a value. The procedure's fallback is GENERIC, which is the one
        // value that claims no rule - the alternative, defaulting to a real rule's name, would put a
        // template in the generator's path that was never written for it.
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
        // Two spellings of the same duplicate, because they reach different branches: the first is
        // caught by the service's pre-check, the second by the unique index. Both must answer the
        // same, or a race would produce a different error from the ordinary path.
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

        // The same code in another case normalises to the same value, so it collides too - which is
        // what keeps the normalisation from creating a second template with the same identity.
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
        // The pattern refuses the shapes that would make a code unusable in the generator's join -
        // spaces, punctuation, a hyphen - and the length bound matches the column.
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

        // The priority is the one field the DTO makes mandatory beyond the two text fields: `{}`
        // would otherwise let a template be created with the column's default and no way to tell
        // that apart from a deliberate 100.
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

    // ==================================================================
    //  57 — PATCH /api/v1/admin/tip-templates/{id}
    // ==================================================================

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
        // The other half of the immutability rule. A client that reads a template, edits a field and
        // PATCHes the whole representation sends the code it read - which is the same code, so it is
        // not a change and must not be refused. Refusing it would make a full-object client
        // impossible, and the client could not tell that from a real conflict.
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
        // The silent-failure guard. Without the service's refusal the procedure would report success,
        // change nothing and record `p_code` - the value the caller sent - in its audit row, so the
        // audit trail would name a code the template does not have. Three assertions, because each
        // alone is satisfiable by a wrong implementation: the status, the stored value, and what the
        // audit row says.
        String token = adminToken();
        String storedCode = randomTipTemplateCode();
        Long id = created(ADMIN_TIP_TEMPLATES_URL, token, Map.of(
                "code", storedCode,
                "titleTemplate", "The template whose code cannot change",
                "bodyTemplate", "Its code is its identity.",
                "defaultPriority", 280)).get("id").asLong();

        // Read before the refused request, because the create above already wrote a
        // TIP_TEMPLATE_SAVED row for this id. An absolute count would be asserting on the create.
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
        // The refusal happens before the call, so the title was not applied either and no audit row
        // was written. That is the stronger form of the guarantee: a refused request changes nothing.
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
        // Normalisation is applied before the comparison, so `suite_x` and `SUITE_X` are the same
        // code and not an edit. Without this a lower-case PATCH would slip past the check and be
        // silently ignored by the procedure - the exact failure the refusal exists to prevent.
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
        // Every field is optional, so `{}` asks for nothing - and the procedure's IFNULL writes make
        // that a no-op rather than a blanking. A service that treated an absent field as "clear it"
        // would fail here on the NOT NULL columns.
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
        // The same argument as the category list: `is_active = false` is how a template is retired,
        // and the toggle's other direction needs the row to still be findable. A list filtered on
        // `is_active` would make the flag a one-way door.
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

    // ==================================================================
    //  Helpers
    // ==================================================================

    private static List<String> codesIn(JsonNode templates) {
        List<String> codes = new ArrayList<>();
        templates.forEach(template -> codes.add(template.get("code").asText()));
        return codes;
    }

    /**
     * Each template's position in the declared order, as one sortable string.
     *
     * <p>{@code defaultPriority} and {@code id} composed rather than checked one after the other,
     * because the order is a pair: comparing the priorities alone cannot tell a stable order from an
     * unstable one when two templates share a priority, and comparing the ids alone cannot tell it from
     * insertion order. The id is zero-padded to twenty digits so the string comparison is the numeric
     * comparison - ids are unsigned 64-bit and a suite run reaches the thousands.
     */
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
