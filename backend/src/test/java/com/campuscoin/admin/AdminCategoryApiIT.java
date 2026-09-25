package com.campuscoin.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Endpoints 49–51: the shared default categories (UC-20, BR-06, BR-07).
 *
 * <p><b>What separates this class from {@code CategoryApiIT}.</b> That suite covers a student's own
 * categories; this one covers the rows that belong to nobody. The two differ in the one thing that
 * matters - who may write them - so the assertions here are about scope: 49 returns exactly
 * {@code user_id IS NULL}, and 51 answers {@code 404} for a student's own row rather than editing it.
 *
 * <p><b>The three refusals this module has to get right, and why each needs a different setup.</b>
 *
 * <ul>
 *   <li>A duplicate name is refused by {@code uk_categories_scope_type_name}, and the service answers
 *       it as a field-level problem - but only within a type, because the key is
 *       {@code (scope, type, name)}. The test that shows the name is reusable across types is what
 *       stops a later "harden the check" from narrowing the key.</li>
 *   <li>A type change is refused when the category is in use, by
 *       {@code trg_categories_before_update}. Reaching that refusal needs a transaction filed under
 *       the category, which is the only way to make the trigger fire - and it is why this suite
 *       creates a real transaction rather than asserting the rule from the service's comments.</li>
 *   <li>An id that is not a default row is a {@code 404}, and the row must not change. That is BR-06
 *       seen from the API: an administrator has no path to a student's personal category.</li>
 * </ul>
 */
class AdminCategoryApiIT extends AbstractAdminApiIT {

    private static final String CATEGORY_ITEM_URL = ADMIN_CATEGORIES_URL + "/%d";
    private static final String PERSONAL_CATEGORIES_URL = "/api/v1/categories";
    private static final String TRANSACTIONS_URL = "/api/v1/transactions";

    /**
     * The fields a default category response carries, as a literal.
     *
     * <p>The same shape the student module publishes, because it is the same type -
     * {@code CategoryResponse} - and that is deliberate rather than incidental: a default category is
     * a category, and a second DTO for the same table would be a second place for the field set to
     * drift.
     */
    private static final List<String> DOCUMENTED_FIELDS = List.of(
            "id", "name", "type", "icon", "color", "isDefault", "isActive", "sortOrder",
            "description");

    // ==================================================================
    //  49 — GET /api/v1/admin/categories
    // ==================================================================

    @Test
    @DisplayName("UC-20: the list is every default category and no student's own")
    void theListIsEveryDefaultAndNoPersonalCategory() throws Exception {
        // A personal category is created first, so "the list excludes them" is a claim about a row
        // that exists rather than about one that was never there. Without it the test would pass
        // against an implementation that had simply not been asked.
        String studentToken = studentToken();
        String personalName = "Personal " + UUID.randomUUID();
        JsonNode personal = created(PERSONAL_CATEGORIES_URL, studentToken,
                Map.of("name", personalName, "type", "EXPENSE"));
        Long personalId = personal.get("id").asLong();

        JsonNode defaults = ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, adminToken(), null);

        assertThat(defaults.isArray()).isTrue();
        assertThat(defaults).hasSizeGreaterThanOrEqualTo(12); // the seeded rows, 5 income + 7 expense
        assertThat(idsOf(defaults)).doesNotContain(personalId);
        assertThat(namesOf(defaults)).doesNotContain(personalName);

        // Every row is a default, which is the property the query's `user_id IS NULL` produces and
        // the flag the client is told apart by.
        for (JsonNode category : defaults) {
            assertThat(category.get("isDefault").asBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("UC-20: the list publishes exactly the documented fields")
    void theListPublishesExactlyTheDocumentedFields() throws Exception {
        JsonNode defaults = ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, adminToken(), null);

        for (JsonNode category : defaults) {
            // A subset rather than equality, because the three nullable fields are omitted when
            // empty rather than sent as null - the convention `CategoryApiIT` pins for the same DTO.
            assertThat(fieldNamesOf(category)).isSubsetOf(DOCUMENTED_FIELDS);
            assertThat(fieldNamesOf(category))
                    .contains("id", "name", "type", "isDefault", "isActive", "sortOrder");
        }
    }

    @Test
    @DisplayName("UC-20: retired defaults are still listed, so BR-07's remedy is reachable")
    void retiredDefaultsAreStillListed() throws Exception {
        // BR-07's answer to "remove a category" is to retire it, and the administrator's way back is
        // to find it in this list. A list that filtered `is_active = 1` would make the flag
        // irreversible, so this pins that the row is there and says it is retired.
        String token = adminToken();
        String name = uniqueName();
        Long id = created(ADMIN_CATEGORIES_URL, token,
                Map.of("name", name, "type", "EXPENSE")).get("id").asLong();

        patched(CATEGORY_ITEM_URL.formatted(id), token, Map.of("isActive", false));

        JsonNode retired = categoryWithId(ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, token, null), id);
        assertThat(retired).as("a retired default must remain readable").isNotNull();
        assertThat(retired.get("isActive").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("UC-20: two identical calls return the same order, and the seeded rows are all there")
    void theListOrderIsStable() throws Exception {
        // `sort_order` is not unique among the defaults - several seeded rows share one - so without
        // the id tie-break in the query two identical calls could return the same rows in different
        // sequences, and a client building a reorder screen would show them shuffling.
        String token = adminToken();

        List<Long> first = idsOf(ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, token, null));
        List<Long> second = idsOf(ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, token, null));

        assertThat(first).isEqualTo(second);
        // The seeded Food and Entertainment rows: proof the list is the real table and not a fixture.
        assertThat(namesOf(ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, token, null)))
                .contains("Food", "Entertainment");
    }

    @Test
    @DisplayName("UC-20: within each type the rows follow sort order - the student picker's sequence")
    void eachTypeIsOrderedBySortOrder() throws Exception {
        JsonNode defaults = ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, adminToken(), null);

        // The assertion stops short of which type comes first on purpose. `categories.type` is an
        // ENUM, and MySQL orders such a column by declaration index while Hibernate may order by the
        // string - both are "ordered by type", and the student module's query orders by the same
        // expression, so the two screens agree either way. Pinning one of the two would be pinning
        // Hibernate's choice, not the contract. What both screens promise and a reorder screen
        // depends on is this: rows of one type are contiguous, and inside a group `sort_order` runs
        // ascending.
        List<String> typeGroups = new java.util.ArrayList<>();
        String previousType = null;
        int previousSortOrder = Integer.MIN_VALUE;
        for (JsonNode category : defaults) {
            String type = category.get("type").asText();
            int sortOrder = category.get("sortOrder").asInt();
            if (!type.equals(previousType)) {
                assertThat(typeGroups).as("a type's rows must be contiguous").doesNotContain(type);
                typeGroups.add(type);
                previousSortOrder = Integer.MIN_VALUE;
            }
            assertThat(sortOrder).isGreaterThanOrEqualTo(previousSortOrder);
            previousType = type;
            previousSortOrder = sortOrder;
        }
        assertThat(typeGroups).contains("INCOME", "EXPENSE");
    }

    // ==================================================================
    //  50 — POST /api/v1/admin/categories
    // ==================================================================

    @Test
    @DisplayName("UC-20: a created category is returned, and its id is the row that was written")
    void creatingReturnsTheRowThatWasCreated() throws Exception {
        // The LAST_INSERT_ID() trap, from the outside: the procedure writes its audit row after the
        // category, so a service that trusted the session's last insert id would return the audit
        // row's id. Cross-checking the response's id against the audit row's `target_id` is what
        // catches that - the two ids differ by construction, and the audit one is the later of them.
        String token = adminToken();
        String name = uniqueName();

        JsonNode response = created(ADMIN_CATEGORIES_URL, token, Map.of(
                "name", name,
                "type", "EXPENSE",
                "icon", "pizza",
                "color", "#AB12CD",
                "description", "Created by the module 11 suite.",
                "sortOrder", 42));

        Long id = response.get("id").asLong();
        assertThat(response.get("name").asText()).isEqualTo(name);
        assertThat(response.get("type").asText()).isEqualTo("EXPENSE");
        assertThat(response.get("icon").asText()).isEqualTo("pizza");
        assertThat(response.get("color").asText()).isEqualTo("#AB12CD");
        assertThat(response.get("sortOrder").asInt()).isEqualTo(42);
        assertThat(response.get("isActive").asBoolean()).isTrue();
        assertThat(response.get("isDefault").asBoolean()).isTrue();

        // The row itself: a default row created by the acting administrator, which is what
        // `created_by` records and what the audit row separately attests to.
        assertThat(stringValueFrom("SELECT IF(user_id IS NULL, 'DEFAULT', 'PERSONAL') "
                + "FROM categories WHERE id = ?", id)).isEqualTo("DEFAULT");
        assertThat(longValueFrom("SELECT created_by FROM categories WHERE id = ?", id))
                .isEqualTo(seededAdminId());

        Long auditId = latestAuditIdFor("CATEGORY_CREATED", "categories", id);
        assertThat(auditActorOf(auditId)).isEqualTo(seededAdminId());
        assertThat(auditDetailFieldOf(auditId, "name")).isEqualTo(name);
        assertThat(auditDetailFieldOf(auditId, "type")).isEqualTo("EXPENSE");
    }

    @Test
    @DisplayName("UC-20: a name already used by a default of the same type is refused as taken")
    void aDuplicateDefaultNameIsRefused() throws Exception {
        String token = adminToken();
        Long id = created(ADMIN_CATEGORIES_URL, token,
                Map.of("name", uniqueName(), "type", "EXPENSE")).get("id").asLong();
        String name = stringValueFrom("SELECT name FROM categories WHERE id = ?", id);

        JsonNode error = refused(HttpMethod.POST, ADMIN_CATEGORIES_URL, token,
                Map.of("name", name, "type", "EXPENSE"),
                HttpStatus.CONFLICT, "CATEGORY_NAME_TAKEN");
        assertThat(error.get("message").asText()).contains(name);

        // `uk_categories_scope_type_name` is the authority; a second row must not exist. Without
        // this the test would pass against a pre-check that refused but let the insert through.
        assertThat(countOf("SELECT COUNT(*) FROM categories "
                + "WHERE user_id IS NULL AND type = 'EXPENSE' AND name = ?", name)).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-20: the same name may be used for the other type, because the key is per type")
    void theSameNameIsAvailableForTheOtherType() throws Exception {
        // `uk_categories_scope_type_name` is (scope, type, name), so "Books" as an expense and
        // "Books" as income are two categories and not a conflict. A test that only ever tried the
        // same type would not notice a service that dropped the type from its check.
        String token = adminToken();
        String name = uniqueName();

        Long expenseId = created(ADMIN_CATEGORIES_URL, token,
                Map.of("name", name, "type", "EXPENSE")).get("id").asLong();
        Long incomeId = created(ADMIN_CATEGORIES_URL, token,
                Map.of("name", name, "type", "INCOME")).get("id").asLong();

        assertThat(incomeId).isNotEqualTo(expenseId);
        assertThat(countOf("SELECT COUNT(*) FROM categories "
                + "WHERE user_id IS NULL AND name = ?", name)).isEqualTo(2);
    }

    @Test
    @DisplayName("UC-20: a personal category of the same name does not block a default one")
    void aPersonalCategoryOfTheSameNameDoesNotBlockADefault() throws Exception {
        // The scope prefix in the unique key exists for this: `scope_key` is IFNULL(user_id, 0), so
        // a student's "Boba" and a default "Boba" occupy different keys. An administrator creates a
        // shared category because students need one - a student having already made a private one is
        // not a reason to refuse.
        String name = uniqueName();
        created(PERSONAL_CATEGORIES_URL, studentToken(), Map.of("name", name, "type", "EXPENSE"));

        JsonNode response = created(ADMIN_CATEGORIES_URL, adminToken(),
                Map.of("name", name, "type", "EXPENSE"));

        assertThat(response.get("id").asLong())
                .isGreaterThan(0L);
        assertThat(countOf("SELECT COUNT(*) FROM categories WHERE name = ?", name)).isEqualTo(2);
    }

    @Test
    @DisplayName("UC-20: a create without a name or a type is a field error, not a server error")
    void aCreateWithoutRequiredFieldsIsAFieldError() throws Exception {
        String token = adminToken();

        JsonNode noName = refused(HttpMethod.POST, ADMIN_CATEGORIES_URL, token,
                Map.of("type", "EXPENSE"), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNamesIn(noName)).containsExactly("name");

        JsonNode noType = refused(HttpMethod.POST, ADMIN_CATEGORIES_URL, token,
                Map.of("name", uniqueName()), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(fieldNamesIn(noType)).containsExactly("type");
    }

    @Test
    @DisplayName("UC-20: a colour that is not #RRGGBB is a field error, matching the CHAR(7) column")
    void anInvalidColourIsAFieldError() throws Exception {
        // `color CHAR(7)` holds exactly `#RRGGBB`, so a value of another length would be truncated or
        // refused by the database. The DTO refuses it first so the client gets an input-level error
        // rather than a conflict naming a constraint.
        JsonNode error = refused(HttpMethod.POST, ADMIN_CATEGORIES_URL, adminToken(),
                Map.of("name", uniqueName(), "type", "EXPENSE", "color", "red"),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");

        assertThat(fieldNamesIn(error)).containsExactly("color");
    }

    // ==================================================================
    //  51 — PATCH /api/v1/admin/categories/{id}
    // ==================================================================

    @Test
    @DisplayName("UC-20: an update returns the stored row and records the previous name")
    void updatingReturnsTheStoredRowAndRecordsTheChange() throws Exception {
        String token = adminToken();
        String originalName = uniqueName();
        Long id = created(ADMIN_CATEGORIES_URL, token,
                Map.of("name", originalName, "type", "EXPENSE", "sortOrder", 10)).get("id").asLong();
        String newName = uniqueName();

        JsonNode response = patched(CATEGORY_ITEM_URL.formatted(id), token, Map.of(
                "name", newName,
                "description", "Renamed by the module 11 suite.",
                "sortOrder", 11,
                "isActive", true));

        assertThat(response.get("id").asLong()).isEqualTo(id);
        assertThat(response.get("name").asText()).isEqualTo(newName);
        assertThat(response.get("sortOrder").asInt()).isEqualTo(11);
        assertThat(response.get("type").asText()).as("a field the request omitted is unchanged")
                .isEqualTo("EXPENSE");

        assertThat(stringValueFrom("SELECT name FROM categories WHERE id = ?", id)).isEqualTo(newName);

        Long auditId = latestAuditIdFor("CATEGORY_UPDATED", "categories", id);
        assertThat(auditActorOf(auditId)).isEqualTo(seededAdminId());
        assertThat(auditDetailFieldOf(auditId, "name")).isEqualTo(newName);
        // The previous name is what makes the audit row readable on its own: after the rename the
        // old value exists nowhere else, and a row that recorded only the new one could not say
        // what changed.
        assertThat(auditDetailFieldOf(auditId, "previousName")).isEqualTo(originalName);
    }

    @Test
    @DisplayName("UC-20: an update with an empty body changes nothing and is still recorded")
    void anEmptyUpdateIsAcceptedAndChangesNothing() throws Exception {
        // Every field of UpdateDefaultCategoryRequest is optional, so `{}` is a valid request that
        // asks for nothing. It is accepted rather than refused, and the procedure's IFNULL writes
        // leave the row exactly as it was - which is worth pinning, because "the update succeeded"
        // and "the update did something" are different claims.
        String token = adminToken();
        String name = uniqueName();
        Long id = created(ADMIN_CATEGORIES_URL, token,
                Map.of("name", name, "type", "INCOME", "sortOrder", 7)).get("id").asLong();

        JsonNode response = patched(CATEGORY_ITEM_URL.formatted(id), token, Map.of());

        assertThat(response.get("name").asText()).isEqualTo(name);
        assertThat(response.get("sortOrder").asInt()).isEqualTo(7);
        assertThat(response.get("type").asText()).isEqualTo("INCOME");
        assertThat(auditActorOf(latestAuditIdFor("CATEGORY_UPDATED", "categories", id)))
                .isEqualTo(seededAdminId());
    }

    @Test
    @DisplayName("BR-07: retiring a default keeps the row and can be undone")
    void retiringKeepsTheRowAndIsReversible() throws Exception {
        String token = adminToken();
        Long id = created(ADMIN_CATEGORIES_URL, token,
                Map.of("name", uniqueName(), "type", "EXPENSE")).get("id").asLong();

        JsonNode retired = patched(CATEGORY_ITEM_URL.formatted(id), token, Map.of("isActive", false));
        assertThat(retired.get("isActive").asBoolean()).isFalse();
        // Retired, not deleted: the row is still there and still has its name attached. A hard
        // delete would break every transaction that referenced it (BR-09 keeps them all).
        assertThat(countOf("SELECT COUNT(*) FROM categories WHERE id = ?", id)).isEqualTo(1);

        Long auditId = latestAuditIdFor("CATEGORY_UPDATED", "categories", id);
        assertThat(auditDetailFieldOf(auditId, "previousActive")).isEqualTo("1");

        JsonNode restored = patched(CATEGORY_ITEM_URL.formatted(id), token, Map.of("isActive", true));
        assertThat(restored.get("isActive").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("BR-06: an update naming a student's own category is a 404, and the row is untouched")
    void aStudentsOwnCategoryIsNotReachable() throws Exception {
        // BR-06 from the API's side. The id is real and the caller is an administrator, so a 403
        // would be the other plausible answer - and it would be wrong, because it would confirm that
        // the id exists and belongs to somebody. The answer is the one an unknown id gets, and the
        // student's row must be exactly as they left it.
        String name = "Personal " + UUID.randomUUID();
        JsonNode personal = created(PERSONAL_CATEGORIES_URL, studentToken(),
                Map.of("name", name, "type", "EXPENSE", "sortOrder", 5));
        Long personalId = personal.get("id").asLong();

        JsonNode error = refused(HttpMethod.PATCH, CATEGORY_ITEM_URL.formatted(personalId),
                adminToken(), Map.of("name", "Hijacked", "isActive", false),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertThat(error.get("message").asText()).isEqualTo("Default category not found.");

        assertThat(stringValueFrom("SELECT name FROM categories WHERE id = ?", personalId))
                .isEqualTo(name);
        assertThat(stringValueFrom("SELECT IF(is_active = 1, 'ACTIVE', 'RETIRED') "
                + "FROM categories WHERE id = ?", personalId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("UC-20: an update naming no category at all is the same 404")
    void anUnknownCategoryIsNotFound() throws Exception {
        JsonNode error = refused(HttpMethod.PATCH,
                CATEGORY_ITEM_URL.formatted(noSuchIdIn("categories")), adminToken(),
                Map.of("name", uniqueName()), HttpStatus.NOT_FOUND, "NOT_FOUND");

        assertThat(error.get("message").asText()).isEqualTo("Default category not found.");
    }

    @Test
    @DisplayName("UC-20: an unused default category may change its type")
    void anUnusedDefaultCategoryMayChangeType() throws Exception {
        // The positive half of the rule the next test refuses. Without it, a service that refused
        // every type change would pass the refusal test and be wrong.
        String token = adminToken();
        Long id = created(ADMIN_CATEGORIES_URL, token,
                Map.of("name", uniqueName(), "type", "EXPENSE")).get("id").asLong();

        JsonNode response = patched(CATEGORY_ITEM_URL.formatted(id), token,
                Map.of("type", "INCOME"));

        assertThat(response.get("type").asText()).isEqualTo("INCOME");
        assertThat(stringValueFrom("SELECT type FROM categories WHERE id = ?", id)).isEqualTo("INCOME");
    }

    @Test
    @DisplayName("BR-05: changing the type of a category with records filed under it is refused")
    void aCategoryInUseMayNotChangeType() throws Exception {
        // The rule the trigger exists for: a transaction's type is its category's type (BR-05), so
        // re-typing a category with records under it would silently reclassify them - an expense
        // becoming income in every report. Reaching the refusal needs a real record, so one is
        // created through the transactions API rather than inserted.
        String token = adminToken();
        Long categoryId = created(ADMIN_CATEGORIES_URL, token,
                Map.of("name", uniqueName(), "type", "EXPENSE")).get("id").asLong();

        String studentToken = studentToken();
        created(TRANSACTIONS_URL, studentToken, Map.of(
                "categoryId", categoryId,
                "amount", "12.50",
                "txnDate", "2026-09-20",
                "description", "Filed under the module 11 suite's category."));

        JsonNode error = refused(HttpMethod.PATCH, CATEGORY_ITEM_URL.formatted(categoryId), token,
                Map.of("type", "INCOME"), HttpStatus.CONFLICT, "CATEGORY_IN_USE");
        assertThat(error.get("message").asText()).contains("type cannot change");

        // Refused, not partially applied: the type is what it was, and the row was not retired or
        // renamed as a side effect of the refused write.
        assertThat(stringValueFrom("SELECT type FROM categories WHERE id = ?", categoryId))
                .isEqualTo("EXPENSE");
    }

    @Test
    @DisplayName("BR-05: a type change to the same type is not a type change")
    void sendingTheStoredTypeBackIsAllowed() throws Exception {
        // A client that round-trips a full representation sends the type it read. That is not a
        // change, so it must not reach the trigger's refusal - otherwise every full-object PATCH on
        // a category in use would fail, and the client could not tell a no-op from a real edit.
        String token = adminToken();
        Long categoryId = created(ADMIN_CATEGORIES_URL, token,
                Map.of("name", uniqueName(), "type", "EXPENSE")).get("id").asLong();

        created(TRANSACTIONS_URL, studentToken(), Map.of(
                "categoryId", categoryId,
                "amount", "3.00",
                "txnDate", "2026-09-21"));

        JsonNode response = patched(CATEGORY_ITEM_URL.formatted(categoryId), token,
                Map.of("type", "EXPENSE", "isActive", false));

        assertThat(response.get("type").asText()).isEqualTo("EXPENSE");
        assertThat(response.get("isActive").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("UC-20: renaming onto a name another default uses is refused as taken")
    void renamingOntoATakenNameIsRefused() throws Exception {
        // The update path is not pre-checked - the trigger deliberately does not check names - so
        // this refusal comes from `uk_categories_scope_type_name` at the write and is classified by
        // the constraint's name. It is the one path where the classifier's durability matters.
        String token = adminToken();
        String takenName = uniqueName();
        Long first = created(ADMIN_CATEGORIES_URL, token,
                Map.of("name", takenName, "type", "EXPENSE")).get("id").asLong();
        Long second = created(ADMIN_CATEGORIES_URL, token,
                Map.of("name", uniqueName(), "type", "EXPENSE")).get("id").asLong();

        refused(HttpMethod.PATCH, CATEGORY_ITEM_URL.formatted(second), token,
                Map.of("name", takenName), HttpStatus.CONFLICT, "CATEGORY_NAME_TAKEN");

        assertThat(stringValueFrom("SELECT name FROM categories WHERE id = ?", first))
                .isEqualTo(takenName);
        assertThat(stringValueFrom("SELECT name FROM categories WHERE id = ?", second))
                .isNotEqualTo(takenName);
    }

    // ==================================================================
    //  Helpers
    // ==================================================================

    private static String uniqueName() {
        return "Suite " + UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<String> namesOf(JsonNode categories) {
        List<String> names = new ArrayList<>();
        categories.forEach(category -> names.add(category.get("name").asText()));
        return names;
    }

    private static JsonNode categoryWithId(JsonNode categories, Long id) {
        for (JsonNode category : categories) {
            if (category.get("id").asLong() == id) {
                return category;
            }
        }
        return null;
    }
}
