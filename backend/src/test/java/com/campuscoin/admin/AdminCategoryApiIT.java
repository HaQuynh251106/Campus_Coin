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

class AdminCategoryApiIT extends AbstractAdminApiIT {

    private static final String CATEGORY_ITEM_URL = ADMIN_CATEGORIES_URL + "/%d";
    private static final String PERSONAL_CATEGORIES_URL = "/api/v1/categories";
    private static final String TRANSACTIONS_URL = "/api/v1/transactions";

    private static final List<String> DOCUMENTED_FIELDS = List.of(
            "id", "name", "type", "icon", "color", "isDefault", "isActive", "sortOrder",
            "description");

    @Test
    @DisplayName("UC-20: the list is every default category and no student's own")
    void theListIsEveryDefaultAndNoPersonalCategory() throws Exception {

        String studentToken = studentToken();
        String personalName = "Personal " + UUID.randomUUID();
        JsonNode personal = created(PERSONAL_CATEGORIES_URL, studentToken,
                Map.of("name", personalName, "type", "EXPENSE"));
        Long personalId = personal.get("id").asLong();

        JsonNode defaults = ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, adminToken(), null);

        assertThat(defaults.isArray()).isTrue();
        assertThat(defaults).hasSizeGreaterThanOrEqualTo(12);
        assertThat(idsOf(defaults)).doesNotContain(personalId);
        assertThat(namesOf(defaults)).doesNotContain(personalName);

        for (JsonNode category : defaults) {
            assertThat(category.get("isDefault").asBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("UC-20: the list publishes exactly the documented fields")
    void theListPublishesExactlyTheDocumentedFields() throws Exception {
        JsonNode defaults = ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, adminToken(), null);

        for (JsonNode category : defaults) {

            assertThat(fieldNamesOf(category)).isSubsetOf(DOCUMENTED_FIELDS);
            assertThat(fieldNamesOf(category))
                    .contains("id", "name", "type", "isDefault", "isActive", "sortOrder");
        }
    }

    @Test
    @DisplayName("UC-20: retired defaults are still listed, so BR-07's remedy is reachable")
    void retiredDefaultsAreStillListed() throws Exception {

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

        String token = adminToken();

        List<Long> first = idsOf(ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, token, null));
        List<Long> second = idsOf(ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, token, null));

        assertThat(first).isEqualTo(second);

        assertThat(namesOf(ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, token, null)))
                .contains("Food", "Entertainment");
    }

    @Test
    @DisplayName("UC-20: within each type the rows follow sort order - the student picker's sequence")
    void eachTypeIsOrderedBySortOrder() throws Exception {
        JsonNode defaults = ok(HttpMethod.GET, ADMIN_CATEGORIES_URL, adminToken(), null);

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

    @Test
    @DisplayName("UC-20: a created category is returned, and its id is the row that was written")
    void creatingReturnsTheRowThatWasCreated() throws Exception {

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

        assertThat(countOf("SELECT COUNT(*) FROM categories "
                + "WHERE user_id IS NULL AND type = 'EXPENSE' AND name = ?", name)).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-20: the same name may be used for the other type, because the key is per type")
    void theSameNameIsAvailableForTheOtherType() throws Exception {

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

        JsonNode error = refused(HttpMethod.POST, ADMIN_CATEGORIES_URL, adminToken(),
                Map.of("name", uniqueName(), "type", "EXPENSE", "color", "red"),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");

        assertThat(fieldNamesIn(error)).containsExactly("color");
    }

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

        assertThat(auditDetailFieldOf(auditId, "previousName")).isEqualTo(originalName);
    }

    @Test
    @DisplayName("UC-20: an update with an empty body changes nothing and is still recorded")
    void anEmptyUpdateIsAcceptedAndChangesNothing() throws Exception {

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

        assertThat(countOf("SELECT COUNT(*) FROM categories WHERE id = ?", id)).isEqualTo(1);

        Long auditId = latestAuditIdFor("CATEGORY_UPDATED", "categories", id);
        assertThat(auditDetailFieldOf(auditId, "previousActive")).isEqualTo("1");

        JsonNode restored = patched(CATEGORY_ITEM_URL.formatted(id), token, Map.of("isActive", true));
        assertThat(restored.get("isActive").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("BR-06: an update naming a student's own category is a 404, and the row is untouched")
    void aStudentsOwnCategoryIsNotReachable() throws Exception {

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

        assertThat(stringValueFrom("SELECT type FROM categories WHERE id = ?", categoryId))
                .isEqualTo("EXPENSE");
    }

    @Test
    @DisplayName("BR-05: a type change to the same type is not a type change")
    void sendingTheStoredTypeBackIsAllowed() throws Exception {

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
