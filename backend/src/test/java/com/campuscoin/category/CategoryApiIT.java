package com.campuscoin.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.campuscoin.support.AbstractMySqlIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * UC-06 over the real HTTP stack: request, security filter, controller, service, repository, MySQL.
 *
 * <p>Every test registers its own student with a random address, so no test depends on another's
 * data. The seeded default categories are read but never modified.
 *
 * <p>What this suite exists to prove is the part a service-level unit test cannot: that a category
 * belonging to another student is unreachable, that the two name-uniqueness rules hold, and that the
 * trigger which refuses a type change on a referenced category is translated into a usable error
 * rather than a 500. The reference is created through the database, because {@code transactions} and
 * {@code budgets} belong to modules that are not built yet - which is exactly the situation the
 * translation exists for.
 */
class CategoryApiIT extends AbstractMySqlIntegrationTest {

    private static final String CATEGORIES_URL = "/api/v1/categories";
    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String ADMIN_LOGIN_URL = "/api/v1/admin/auth/login";

    private static final String PASSWORD = "Student@123";

    /** A name seeded as a default EXPENSE category, used to test the shadowing rule. */
    private static final String DEFAULT_EXPENSE_NAME = "Food";

    /** A name seeded as a default INCOME category. */
    private static final String DEFAULT_INCOME_NAME = "Allowance";

    /** How many default categories {@code db/05_seed.sql} creates: 5 income and 7 expense. */
    private static final int SEEDED_DEFAULT_CATEGORY_COUNT = 12;

    /**
     * The complete set of properties {@code CategoryResponse} may send.
     *
     * <p>Listed here as a literal rather than derived from the class, so that adding a field to the
     * DTO fails this test instead of quietly widening the published contract.
     */
    private static final List<String> DOCUMENTED_FIELDS = List.of(
            "id", "name", "type", "icon", "color", "isDefault", "isActive", "sortOrder",
            "description");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    //  UC-06 read
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-06: a new student sees the shared default categories and none of their own")
    void newStudentSeesTheDefaultsAndNoPersonalCategories() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response = send(HttpMethod.GET, CATEGORIES_URL, token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(SEEDED_DEFAULT_CATEGORY_COUNT);

        // Every one is a shared default: user_id IS NULL, so isDefault is true and the student may
        // not edit or delete any of them.
        List<Boolean> defaults = new ArrayList<>();
        body.forEach(category -> defaults.add(category.get("isDefault").asBoolean()));
        assertThat(defaults).containsOnly(true);

        // Both types are present, which a picker needs.
        List<String> types = new ArrayList<>();
        body.forEach(category -> types.add(category.get("type").asText()));
        assertThat(types).contains("INCOME", "EXPENSE");

        // The agreed field contract: no owner identifier, no creation metadata, nothing else.
        // It is a closed set in the direction that matters - every field present is expected -
        // while the three nullable ones are absent rather than null for these rows, because no
        // seeded default carries an icon, a colour or a description.
        body.forEach(category -> {
            assertThat(category.fieldNames()).toIterable()
                    .isSubsetOf(DOCUMENTED_FIELDS);
            assertThat(category.fieldNames()).toIterable()
                    .contains("id", "name", "type", "isDefault", "isActive", "sortOrder");
        });
    }

    @Test
    @DisplayName("UC-06: a nullable field is left out when it has no value, not sent as null")
    void absentNullableFieldsAreOmittedFromTheResponse() throws Exception {
        // The Angular model declares icon, color and description as optional, which is only
        // truthful if the API omits them instead of filling them with null. This pins that, since
        // a @JsonInclude change would silently start sending nulls.
        String token = loginNewStudent();

        JsonNode bare = objectMapper.readTree(
                create(token, Map.of("name", "No Extras", "type", "EXPENSE")).getBody());
        assertThat(bare.fieldNames()).toIterable().doesNotContain("icon", "color", "description");

        JsonNode decorated = objectMapper.readTree(create(token, Map.of(
                "name", "Extras", "type", "EXPENSE", "icon", "tag",
                "color", "#123456", "description", "with everything")).getBody());
        assertThat(decorated.get("icon").asText()).isEqualTo("tag");
        assertThat(decorated.get("description").asText()).isEqualTo("with everything");
    }

    @Test
    @DisplayName("UC-06: a created category appears in the list alongside the defaults")
    void createdCategoryIsListedWithTheDefaults() throws Exception {
        String token = loginNewStudent();

        JsonNode created = objectMapper.readTree(
                create(token, Map.of("name", "Campus Cafe", "type", "EXPENSE")).getBody());
        Long categoryId = created.get("id").asLong();

        JsonNode list = objectMapper.readTree(
                send(HttpMethod.GET, CATEGORIES_URL, token, null).getBody());
        assertThat(list).hasSize(SEEDED_DEFAULT_CATEGORY_COUNT + 1);

        // The student's own category is the only one that is not a default.
        List<String> ownNames = new ArrayList<>();
        list.forEach(category -> {
            if (!category.get("isDefault").asBoolean()) {
                ownNames.add(category.get("name").asText());
            }
        });
        assertThat(ownNames).containsExactly("Campus Cafe");

        // The documented order is by type, then by display order. This new row has sort_order 0
        // where the seeded EXPENSE categories start at 10, so it comes first within its type: a
        // student's own category is not pushed to the bottom of their own picker.
        JsonNode firstExpense = firstOfType(list, "EXPENSE");
        assertThat(firstExpense.get("id").asLong()).isEqualTo(categoryId);

        // The ordering holds across the whole list, which is what a picker relies on. The type
        // comparison follows the database's ENUM order - INCOME is declared first, so income
        // categories are listed before expense ones - rather than the alphabetical order of the
        // two names, which is the opposite.
        List<String> sortKeys = new ArrayList<>();
        list.forEach(category -> sortKeys.add(
                typeRank(category.get("type").asText()) + "/" + category.get("sortOrder").asInt()));
        assertThat(sortKeys).isSorted();
    }

    @Test
    @DisplayName("UC-06: on a tie of type and display order the default is listed first")
    void defaultsComeFirstOnATieOfDisplayOrder() throws Exception {
        // The `user_id ASC` tie-break in the query decides only this case, so it is worth pinning:
        // given the same type and the same sort_order, the shared default is offered before the
        // student's own row. A new personal category normally sits at sort_order 0 and so comes
        // first on its own; this is where the two collide.
        String token = loginNewStudent();

        // 'Scholarship' is the seeded INCOME category with sort_order 3.
        createAndReturnId(token, Map.of("name", "Sort Probe", "type", "INCOME", "sortOrder", 3));

        JsonNode list = objectMapper.readTree(
                send(HttpMethod.GET, CATEGORIES_URL, token, null).getBody());

        List<String> namesAtOrderThree = new ArrayList<>();
        list.forEach(category -> {
            if ("INCOME".equals(category.get("type").asText())
                    && category.get("sortOrder").asInt() == 3) {
                namesAtOrderThree.add(category.get("name").asText());
            }
        });

        assertThat(namesAtOrderThree).containsExactly("Scholarship", "Sort Probe");
    }

    /** The first listed category of a type - what a picker shows at the top of that group. */
    private JsonNode firstOfType(JsonNode list, String type) {
        for (JsonNode category : list) {
            if (type.equals(category.get("type").asText())) {
                return category;
            }
        }
        throw new AssertionError("No " + type + " category in the list.");
    }

    /** Mirrors the ENUM's declaration order, which is what MySQL actually sorts on. */
    private static int typeRank(String type) {
        return "INCOME".equals(type) ? 1 : 2;
    }

    @Test
    @DisplayName("UC-06: one own category can be read back by id")
    void ownCategoryCanBeReadById() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createAndReturnId(token, Map.of("name", "Coffee", "type", "EXPENSE"));

        ResponseEntity<String> response =
                send(HttpMethod.GET, CATEGORIES_URL + "/" + categoryId, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("id").asLong()).isEqualTo(categoryId);
        assertThat(body.get("name").asText()).isEqualTo("Coffee");
        assertThat(body.get("isDefault").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------
    //  UC-06 create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-06: a personal category is created with every field it was sent")
    void personalCategoryPersistsEveryField() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response = create(token, Map.of(
                "name", "Boba",
                "type", "EXPENSE",
                "icon", "coffee",
                "color", "#f59e0b",
                "description", "Study snacks",
                "sortOrder", 5,
                "isActive", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("name").asText()).isEqualTo("Boba");
        assertThat(body.get("type").asText()).isEqualTo("EXPENSE");
        assertThat(body.get("icon").asText()).isEqualTo("coffee");
        // Normalised to upper case, which is the form the seed data and the frontend use.
        assertThat(body.get("color").asText()).isEqualTo("#F59E0B");
        assertThat(body.get("description").asText()).isEqualTo("Study snacks");
        assertThat(body.get("sortOrder").asInt()).isEqualTo(5);
        assertThat(body.get("isActive").asBoolean()).isTrue();
        assertThat(body.get("isDefault").asBoolean()).isFalse();

        // The response is what was written; the row is the authority. Read it back.
        Long categoryId = body.get("id").asLong();
        assertThat(columnInDatabase(categoryId, "name")).isEqualTo("Boba");
        assertThat(columnInDatabase(categoryId, "color")).isEqualTo("#F59E0B");
        assertThat(columnInDatabase(categoryId, "sort_order")).isEqualTo("5");
    }

    @Test
    @DisplayName("UC-06: the minimum body creates a usable category with the schema defaults")
    void minimalBodyUsesTheColumnDefaults() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response = create(token, Map.of("name", "Bare Minimum", "type", "INCOME"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(response.getBody());
        // sort_order SMALLINT NOT NULL DEFAULT 0 and is_active TINYINT(1) NOT NULL DEFAULT 1.
        assertThat(body.get("sortOrder").asInt()).isZero();
        assertThat(body.get("isActive").asBoolean()).isTrue();
        // The nullable columns are absent from the response rather than null.
        assertThat(body.has("icon")).isFalse();
        assertThat(body.has("color")).isFalse();
        assertThat(body.has("description")).isFalse();
    }

    @Test
    @DisplayName("UC-06: whitespace is trimmed, and a blank optional field stores null")
    void optionalTextIsTrimmedAndEmptied() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response = create(token, Map.of(
                "name", "  Padded Name  ",
                "type", "EXPENSE",
                "icon", "   ",
                "description", "   "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("name").asText()).isEqualTo("Padded Name");

        // A whitespace-only optional field is treated as "not set" and stored as null, not as
        // a string of spaces that would render as an empty label with an invisible value.
        Long categoryId = body.get("id").asLong();
        assertThat(columnInDatabase(categoryId, "icon")).isNull();
        assertThat(columnInDatabase(categoryId, "description")).isNull();
    }

    @Test
    @DisplayName("UC-06: a name longer than the column is a 400, not a truncated row")
    void nameLengthBoundaryMatchesTheColumn() throws Exception {
        String token = loginNewStudent();

        // 81 characters would overflow name VARCHAR(80).
        assertFieldError(token, Map.of("name", "A".repeat(81), "type", "EXPENSE"), "name");
        assertFieldError(token, Map.of("name", "   ", "type", "EXPENSE"), "name");

        // 80 is inside the column, and padding is measured after trimming.
        assertThat(create(token, Map.of("name", "A".repeat(80), "type", "EXPENSE"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create(token, Map.of("name", "  " + "B".repeat(80) + "  ", "type", "EXPENSE"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("UC-06: a multi-line description is accepted, and its length is still bounded")
    void multiLineDescriptionIsAccepted() throws Exception {
        // The documented rule for `description` is a length limit and nothing else, and the column
        // is a VARCHAR, which holds a newline. A note written over two lines is therefore valid and
        // must not be refused by a pattern that measures one line of it.
        String token = loginNewStudent();

        ResponseEntity<String> response = create(token, Map.of(
                "name", "Two Line Note", "type", "EXPENSE",
                "description", "Coffee before the 8am lecture.\nCheaper at the campus kiosk."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(response.getBody()).get("description").asText())
                .contains("8am lecture", "campus kiosk");

        // The bound is unchanged: 255 padded characters pass, 256 do not.
        Map<String, Object> tooLong = new LinkedHashMap<>();
        tooLong.put("name", "Too Long Note");
        tooLong.put("type", "EXPENSE");
        tooLong.put("description", "x".repeat(256));
        assertFieldError(token, tooLong, "description");
    }

    @Test
    @DisplayName("UC-06: a colour that is not #RRGGBB is a 400")
    void malformedColourIsRejected() throws Exception {
        String token = loginNewStudent();

        assertFieldError(token, Map.of("name", "Colour One", "type", "EXPENSE", "color", "red"),
                "color");
        assertFieldError(token, Map.of("name", "Colour Two", "type", "EXPENSE", "color", "#FFF"),
                "color");
        assertFieldError(token, Map.of("name", "Colour Three", "type", "EXPENSE", "color", "#GGGGGG"),
                "color");

        // The exact shape is accepted, in either case.
        assertThat(create(token, Map.of("name", "Colour Four", "type", "EXPENSE",
                "color", "#0a1B2c")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("UC-06: an unrecognised type is a 400 naming the field")
    void unknownTypeIsRejectedPerField() throws Exception {
        String token = loginNewStudent();

        assertFieldError(token, Map.of("name", "Bad Type", "type", "TRANSFER"), "type");
        // Lower case is not a member either: the contract is the database's vocabulary.
        assertFieldError(token, Map.of("name", "Bad Case", "type", "expense"), "type");
        // A number is never resolved to an enum ordinal.
        assertFieldError(token, Map.of("name", "Ordinal", "type", 0), "type");
    }

    @Test
    @DisplayName("UC-06: name and type are required")
    void missingRequiredFieldsAreReported() throws Exception {
        String token = loginNewStudent();

        assertFieldError(token, Map.of("type", "EXPENSE"), "name");
        assertFieldError(token, Map.of("name", "No Type Given"), "type");
    }

    // ------------------------------------------------------------------
    //  Name uniqueness (uk_categories_scope_type_name, trg_categories_before_insert)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-06: a student cannot have two categories with the same name and type")
    void duplicateNameForTheSameTypeIsRefused() throws Exception {
        String token = loginNewStudent();
        create(token, Map.of("name", "Coffee", "type", "EXPENSE"));

        ResponseEntity<String> response = create(token, Map.of("name", "Coffee", "type", "EXPENSE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode error = objectMapper.readTree(response.getBody());
        assertThat(error.get("errorCode").asText()).isEqualTo("CATEGORY_NAME_TAKEN");
        // Section 7.7: the constraint name and the SQL statement never reach the client.
        assertThat(response.getBody()).doesNotContain("uk_categories_scope_type_name", "INSERT",
                "SELECT", "java.", "hibernate");
    }

    @Test
    @DisplayName("UC-06: the same name is allowed under the other type")
    void sameNameIsAllowedForADifferentType() throws Exception {
        String token = loginNewStudent();

        // The unique key is on (scope_key, type, name), so the type is part of the identity. A
        // student may legitimately have "Gift" as both money received and money spent.
        assertThat(create(token, Map.of("name", "Gift Received", "type", "INCOME"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create(token, Map.of("name", "Gift Received", "type", "EXPENSE"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("UC-06/BR-06: a personal category cannot take a default category's name")
    void personalCategoryCannotShadowADefaultOne() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response = create(token,
                Map.of("name", DEFAULT_EXPENSE_NAME, "type", "EXPENSE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("CATEGORY_NAME_TAKEN");
    }

    @Test
    @DisplayName("BR-06: a default name is blocked by the trigger too, not only by the pre-check")
    void defaultNameRuleIsEnforcedByTheDatabase() throws Exception {
        // The service checks first, so the API path never reaches the trigger. This walks around
        // the service entirely and inserts the row directly, which is what proves the database is
        // the authority rather than a second opinion: the trigger refuses it for every caller,
        // including a hand-run statement.
        Long studentId = registerAndReturnId();

        assertThatThrownBy(() -> {
            try (Connection connection = openDatabaseConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO categories (user_id, name, type, created_by) VALUES (?, ?, ?, ?)")) {
                statement.setLong(1, studentId);
                statement.setString(2, DEFAULT_EXPENSE_NAME);
                statement.setString(3, "EXPENSE");
                statement.setLong(4, studentId);
                statement.executeUpdate();
            }
        }).isInstanceOf(SQLException.class)
          .hasMessageContaining("BR-06");
    }

    // ------------------------------------------------------------------
    //  UC-06 update
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-06: a partial update changes only the field it names")
    void partialUpdateLeavesTheOtherFieldsAlone() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createAndReturnId(token, Map.of(
                "name", "Original", "type", "EXPENSE", "icon", "tag", "color", "#112233",
                "description", "Original note", "sortOrder", 3));

        ResponseEntity<String> response = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of("description", "Updated note"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("description").asText()).isEqualTo("Updated note");
        assertThat(body.get("name").asText()).isEqualTo("Original");
        assertThat(body.get("icon").asText()).isEqualTo("tag");
        assertThat(body.get("color").asText()).isEqualTo("#112233");
        assertThat(body.get("sortOrder").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("UC-06: an empty update body changes nothing and is not an error")
    void emptyUpdateBodyChangesNothing() throws Exception {
        // A careless frontend can send {} - every field left out because none was edited.
        String token = loginNewStudent();
        Long categoryId = createAndReturnId(token, Map.of(
                "name", "Untouched", "type", "EXPENSE", "icon", "tag", "color", "#112233",
                "sortOrder", 4));
        String before = rowSnapshot(categoryId);

        ResponseEntity<String> response = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).get("name").asText())
                .isEqualTo("Untouched");
        // No field changed, so Hibernate's dirty checking finds nothing to write.
        assertThat(rowSnapshot(categoryId)).isEqualTo(before);
    }

    @Test
    @DisplayName("UC-06: an empty string clears a nullable text field")
    void emptyStringClearsANullableField() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createAndReturnId(token, Map.of(
                "name", "Clearable", "type", "EXPENSE", "icon", "tag",
                "color", "#112233", "description", "note"));

        ResponseEntity<String> response = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of("icon", "", "color", "", "description", ""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.has("icon")).isFalse();
        assertThat(body.has("color")).isFalse();
        assertThat(body.has("description")).isFalse();
        assertThat(columnInDatabase(categoryId, "icon")).isNull();
        assertThat(columnInDatabase(categoryId, "color")).isNull();
    }

    @Test
    @DisplayName("UC-06: BR-07 retire and restore work through isActive")
    void retireAndRestoreThroughIsActive() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createAndReturnId(token, Map.of("name", "Retirable", "type", "EXPENSE"));

        ResponseEntity<String> retired = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of("isActive", false));
        assertThat(retired.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(retired.getBody()).get("isActive").asBoolean()).isFalse();
        assertThat(columnInDatabase(categoryId, "is_active")).isEqualTo("0");

        // A retired category stays in the list rather than vanishing, so it can be found again.
        JsonNode list = objectMapper.readTree(
                send(HttpMethod.GET, CATEGORIES_URL, token, null).getBody());
        assertThat(list.size()).isEqualTo(SEEDED_DEFAULT_CATEGORY_COUNT + 1);

        ResponseEntity<String> restored = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of("isActive", true));
        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(columnInDatabase(categoryId, "is_active")).isEqualTo("1");
    }

    @Test
    @DisplayName("UC-06: a rename that collides is refused, and one that does not is applied")
    void renameHonoursUniqueness() throws Exception {
        String token = loginNewStudent();
        create(token, Map.of("name", "Existing", "type", "EXPENSE"));
        Long categoryId = createAndReturnId(token, Map.of("name", "Renamable", "type", "EXPENSE"));

        ResponseEntity<String> collision = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of("name", "Existing"));
        assertThat(collision.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(columnInDatabase(categoryId, "name")).isEqualTo("Renamable");

        // Renaming a category to the name it already has is not a collision with itself.
        assertThat(send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId, token,
                Map.of("name", "Renamable")).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> renamed = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of("name", "Renamed"));
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(columnInDatabase(categoryId, "name")).isEqualTo("Renamed");
    }

    @Test
    @DisplayName("UC-06/BR-06: a rename cannot take a default category's name either")
    void renameCannotTakeADefaultName() throws Exception {
        // The insert trigger refuses a personal category that shadows a default one, but the update
        // trigger checks only scope and type. Without this check a student could reach by rename
        // exactly the state the insert rule exists to prevent.
        String token = loginNewStudent();
        Long categoryId = createAndReturnId(token, Map.of("name", "Mine", "type", "EXPENSE"));

        ResponseEntity<String> response = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of("name", DEFAULT_EXPENSE_NAME));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("CATEGORY_NAME_TAKEN");
        assertThat(columnInDatabase(categoryId, "name")).isEqualTo("Mine");
    }

    @Test
    @DisplayName("UC-06: changing the type is allowed while nothing references the category")
    void typeChangeIsAllowedWhenUnreferenced() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createAndReturnId(token, Map.of("name", "Movable", "type", "EXPENSE"));

        ResponseEntity<String> response = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of("type", "INCOME"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).get("type").asText()).isEqualTo("INCOME");
        assertThat(columnInDatabase(categoryId, "type")).isEqualTo("INCOME");
    }

    @Test
    @DisplayName("BR-05: changing the type of a referenced category is refused as a 409")
    void typeChangeOnAReferencedCategoryIsRefused() throws Exception {
        String token = loginNewStudent();
        Long studentId = userIdOfNewStudent(token);
        Long categoryId = createAndReturnId(token, Map.of("name", "Used", "type", "EXPENSE"));

        // The reference is made directly, because transactions belong to module 4. This is the
        // situation the translation exists for: the trigger raises SIGNAL SQLSTATE 45000, which the
        // persistence layer wraps in an exception that is not a DataIntegrityViolationException.
        insertTransaction(studentId, categoryId, "referenced");

        ResponseEntity<String> response = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of("type", "INCOME"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode error = objectMapper.readTree(response.getBody());
        assertThat(error.get("errorCode").asText()).isEqualTo("CATEGORY_IN_USE");
        // The trigger message is not forwarded verbatim; the API explains it in its own words.
        assertThat(response.getBody()).doesNotContain("SIGNAL", "trg_categories", "SQLSTATE");

        // The type is unchanged: the whole point of the rule.
        assertThat(columnInDatabase(categoryId, "type")).isEqualTo("EXPENSE");
    }

    @Test
    @DisplayName("UC-06: a rename of a referenced category is still allowed")
    void renameOfAReferencedCategoryIsAllowed() throws Exception {
        // The trigger blocks only a type change on a referenced category. A name change rewrites no
        // history - the reports join by id - so refusing it would be stricter than the schema and
        // would stop a student from fixing a typo in a category they use daily.
        String token = loginNewStudent();
        Long studentId = userIdOfNewStudent(token);
        Long categoryId = createAndReturnId(token, Map.of("name", "Typo Nmae", "type", "EXPENSE"));
        insertTransaction(studentId, categoryId, "typo owner");

        ResponseEntity<String> response = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId,
                token, Map.of("name", "Typo Name"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(columnInDatabase(categoryId, "name")).isEqualTo("Typo Name");
    }

    // ------------------------------------------------------------------
    //  UC-06 delete, and BR-07
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-06: an unused category is deleted")
    void unusedCategoryIsDeleted() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createAndReturnId(token, Map.of("name", "Disposable", "type", "EXPENSE"));

        ResponseEntity<String> response =
                send(HttpMethod.DELETE, CATEGORIES_URL + "/" + categoryId, token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(rowExists(categoryId)).isFalse();
        // The list matches the seed again.
        assertThat(objectMapper.readTree(send(HttpMethod.GET, CATEGORIES_URL, token, null).getBody()))
                .hasSize(SEEDED_DEFAULT_CATEGORY_COUNT);
    }

    @Test
    @DisplayName("BR-07: deleting a category that has a budget is refused as a 409")
    void categoryWithABudgetCannotBeDeleted() throws Exception {
        String token = loginNewStudent();
        Long studentId = userIdOfNewStudent(token);
        Long categoryId = createAndReturnId(token, Map.of("name", "Budgeted", "type", "EXPENSE"));
        insertBudget(studentId, categoryId);

        ResponseEntity<String> response =
                send(HttpMethod.DELETE, CATEGORIES_URL + "/" + categoryId, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("CATEGORY_IN_USE");
        // The row survives; the caller is told to retire it instead.
        assertThat(rowExists(categoryId)).isTrue();

        // And the documented remedy works.
        assertThat(send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId, token,
                Map.of("isActive", false)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("BR-07: deleting a category that has a transaction is refused as a 409")
    void categoryWithATransactionCannotBeDeleted() throws Exception {
        // A different mechanism reaches the same answer: here the restricting foreign key is what
        // refuses, not the trigger. Both must be recognised, because a client cannot act on which
        // one fired.
        String token = loginNewStudent();
        Long studentId = userIdOfNewStudent(token);
        Long categoryId = createAndReturnId(token, Map.of("name", "Has Txn", "type", "EXPENSE"));
        Long transactionId = insertTransaction(studentId, categoryId, "must survive");

        ResponseEntity<String> response =
                send(HttpMethod.DELETE, CATEGORIES_URL + "/" + categoryId, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                .isEqualTo("CATEGORY_IN_USE");
        assertThat(response.getBody()).doesNotContain("fk_txn_category", "constraint", "SQL");
        assertThat(rowExists(categoryId)).isTrue();

        // The refusal is not a rollback of something else: the transaction it is protecting is
        // untouched, and the CREATE row the trigger wrote for it is still there. Reported records
        // outlive the categories they were filed under, which is the whole reason for BR-07.
        assertThat(transactionExists(transactionId)).isTrue();
        assertThat(historyRowsFor(transactionId)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    //  Ownership (BR-02) and authorization
    // ------------------------------------------------------------------

    @Test
    @DisplayName("BR-02: another student's category is invisible and unreachable")
    void anotherStudentsCategoryIsUnreachable() throws Exception {
        String firstToken = loginNewStudent();
        String secondToken = loginNewStudent();
        Long firstCategoryId = createAndReturnId(firstToken,
                Map.of("name", "Private", "type", "EXPENSE", "description", "not yours"));

        // READ: 404 rather than 403, so the response does not confirm the category exists.
        assertThat(send(HttpMethod.GET, CATEGORIES_URL + "/" + firstCategoryId, secondToken, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // UPDATE: refused, and the row is untouched.
        assertThat(send(HttpMethod.PATCH, CATEGORIES_URL + "/" + firstCategoryId, secondToken,
                Map.of("name", "Hijacked")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(columnInDatabase(firstCategoryId, "name")).isEqualTo("Private");
        assertThat(columnInDatabase(firstCategoryId, "description")).isEqualTo("not yours");

        // DELETE: refused, and the row survives.
        assertThat(send(HttpMethod.DELETE, CATEGORIES_URL + "/" + firstCategoryId, secondToken, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rowExists(firstCategoryId)).isTrue();

        // The second student's own list does not contain it.
        assertThat(send(HttpMethod.GET, CATEGORIES_URL, secondToken, null).getBody())
                .doesNotContain("Private");
        // And the owner's does.
        assertThat(send(HttpMethod.GET, CATEGORIES_URL, firstToken, null).getBody())
                .contains("Private");
    }

    @Test
    @DisplayName("BR-06: a default category cannot be edited or deleted by a student")
    void defaultCategoriesAreReadOnlyForStudents() throws Exception {
        String token = loginNewStudent();
        Long defaultCategoryId = defaultCategoryId("Transport");
        String before = rowSnapshot(defaultCategoryId);

        // A default category has user_id IS NULL, so findByIdAndUserId never matches it. The answer
        // is 404 rather than 403 for the same reason as above - it is not the caller's row, and the
        // student's route to it does not exist at all. Administering a default category is UC-20.
        assertThat(send(HttpMethod.GET, CATEGORIES_URL + "/" + defaultCategoryId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.PATCH, CATEGORIES_URL + "/" + defaultCategoryId, token,
                Map.of("name", "Renamed By Student")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.PATCH, CATEGORIES_URL + "/" + defaultCategoryId, token,
                Map.of("isActive", false)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.DELETE, CATEGORIES_URL + "/" + defaultCategoryId, token, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Not one column of the shared row changed, and every default is still there.
        assertThat(rowSnapshot(defaultCategoryId)).isEqualTo(before);
        assertThat(countDefaults()).isEqualTo(SEEDED_DEFAULT_CATEGORY_COUNT);
    }

    @Test
    @DisplayName("Section 7.5: every category endpoint refuses an anonymous caller")
    void categoryEndpointsRejectAnonymousCallers() {
        assertThat(send(HttpMethod.GET, CATEGORIES_URL, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.GET, CATEGORIES_URL + "/1", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.POST, CATEGORIES_URL, null,
                Map.of("name", "Anonymous", "type", "EXPENSE")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.PATCH, CATEGORIES_URL + "/1", null,
                Map.of("name", "Anonymous")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.DELETE, CATEGORIES_URL + "/1", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("BR-03: signing out revokes the session, and the token stops working here")
    void revokedSessionIsRefused() throws Exception {
        // UC-02 B5. The token is still cryptographically valid and unexpired, so only the session
        // check in the token filter can stop these calls.
        String email = randomEmail();
        register(email);
        String token = login(email);

        assertThat(send(HttpMethod.GET, CATEGORIES_URL, token, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        send(HttpMethod.POST, "/api/v1/auth/logout", token, null);

        assertThat(send(HttpMethod.GET, CATEGORIES_URL, token, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.POST, CATEGORIES_URL, token,
                Map.of("name", "After Logout", "type", "EXPENSE")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.DELETE, CATEGORIES_URL + "/1", token, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("BR-03: a disabled account is refused here as UNAUTHENTICATED, not ACCOUNT_DISABLED")
    void disabledAccountIsRefusedAsUnauthenticated() throws Exception {
        String email = randomEmail();
        register(email);
        String token = login(email);
        Long userId = userIdOf(email);

        setAccountStatus(userId, "DISABLED");

        // The account is stopped by the token filter, before the controller is reached. That filter's
        // entry point reports every reason it refuses a token with the same code and message, so the
        // holder of a token cannot tell "your account is disabled" from "your token is no good" - or
        // from a revoked session, or a stale token_version. ACCOUNT_DISABLED is the sign-in code,
        // where the student needs to be told why they cannot get in; docs/api/categories.md says so.
        ResponseEntity<String> read = send(HttpMethod.GET, CATEGORIES_URL, token, null);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(objectMapper.readTree(read.getBody()).get("errorCode").asText())
                .isEqualTo("UNAUTHENTICATED");

        ResponseEntity<String> write = send(HttpMethod.POST, CATEGORIES_URL, token,
                Map.of("name", "Disabled Account", "type", "EXPENSE"));
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(objectMapper.readTree(write.getBody()).get("errorCode").asText())
                .isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("Section 7.5: an administrator token is refused by the student category API")
    void administratorTokenIsRefused() throws Exception {
        // The administrator route to `categories` is UC-20, which is sp_admin_upsert_default_category
        // under /api/v1/admin/**. Letting an administrator through here would write a personal row
        // owned by that administrator, quietly creating a second path to the same table.
        String adminToken = adminLogin();

        assertThat(send(HttpMethod.GET, CATEGORIES_URL, adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, CATEGORIES_URL, adminToken,
                Map.of("name", "Admin Own", "type", "EXPENSE")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Section 7.5: a category request cannot set the owner or the scope")
    void categoryRequestCannotSetOwnership() throws Exception {
        String token = loginNewStudent();
        Long studentId = userIdOfNewStudent(token);

        // Extra keys the contract does not define. Jackson ignores unknown properties, so the risk
        // is not that they are read - it is that a later refactor maps the DTO onto the entity and
        // starts honouring them. Asserting the outcome guards against both.
        Map<String, Object> hostile = new LinkedHashMap<>();
        hostile.put("name", "Legitimate");
        hostile.put("type", "EXPENSE");
        hostile.put("userId", 1L);
        hostile.put("user_id", 1L);
        hostile.put("isDefault", true);
        hostile.put("createdBy", 1L);
        hostile.put("id", 99999L);

        ResponseEntity<String> response = send(HttpMethod.POST, CATEGORIES_URL, token, hostile);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(response.getBody());
        Long categoryId = body.get("id").asLong();

        // Owned by the caller, not by the id that was sent, and personal rather than a default.
        assertThat(columnInDatabase(categoryId, "user_id")).isEqualTo(String.valueOf(studentId));
        assertThat(columnInDatabase(categoryId, "created_by")).isEqualTo(String.valueOf(studentId));
        assertThat(body.get("isDefault").asBoolean()).isFalse();
        assertThat(rowExists(99999L)).isFalse();

        // No owner field is echoed back.
        assertThat(body.has("userId")).isFalse();
        assertThat(body.has("createdBy")).isFalse();
    }

    @Test
    @DisplayName("Section 7.5: the scope of an existing category cannot be changed")
    void scopeCannotBeChangedByRequest() throws Exception {
        String token = loginNewStudent();
        Long studentId = userIdOfNewStudent(token);
        Long categoryId = createAndReturnId(token, Map.of("name", "Scoped", "type", "EXPENSE"));

        // Sending userId: null would make the row a default category if it were honoured - a
        // privilege escalation, since default categories are visible to everyone.
        Map<String, Object> hostile = new LinkedHashMap<>();
        hostile.put("userId", null);
        hostile.put("user_id", null);
        hostile.put("isDefault", true);

        send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId, token, hostile);

        assertThat(columnInDatabase(categoryId, "user_id")).isEqualTo(String.valueOf(studentId));
    }

    // ------------------------------------------------------------------
    //  Duplicate, concurrency and error consistency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-06: repeating an identical update is idempotent")
    void repeatedIdenticalUpdatesAreIdempotent() throws Exception {
        String token = loginNewStudent();
        Long categoryId = createAndReturnId(token, Map.of("name", "Idempotent", "type", "EXPENSE"));

        Map<String, Object> body = Map.of("name", "Same Name", "sortOrder", 4, "icon", "tag");
        assertThat(send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId, token, body)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        String afterFirst = rowSnapshot(categoryId);

        assertThat(send(HttpMethod.PATCH, CATEGORIES_URL + "/" + categoryId, token, body)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(rowSnapshot(categoryId)).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("UC-06: a duplicate create is refused the second time")
    void duplicateCreateIsRefused() throws Exception {
        String token = loginNewStudent();
        Map<String, Object> body = Map.of("name", "Double Submit", "type", "EXPENSE");

        assertThat(create(token, body).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> second = create(token, body);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Exactly one row, which is what a double-clicked save button must produce.
        assertThat(countOwnCategoriesWithName(token, "Double Submit")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Section 7.6: no internal identifier reaches the log when a write is refused")
    void refusalsDoNotWriteDatabaseInternalsToTheLog() throws Exception {
        // Every refusal in this module is a normal outcome of normal use, so it logs at INFO with
        // the operation and the user id but not the exception. The driver's text is what has to
        // stay out: a duplicate-key message names the constraint and the scope key, and a SIGNAL
        // message names the trigger's table. The response already withholds all three, and the log
        // is the other place they could surface.
        //
        // The assertions below cover the response. The log half is asserted where the log stream is
        // captured, in SecurityHardeningIT, because a per-class appender would not see the
        // application's own logger configuration.
        String token = loginNewStudent();
        create(token, Map.of("name", "Logged Once", "type", "EXPENSE"));

        String duplicate = create(token, Map.of("name", "Logged Once", "type", "EXPENSE")).getBody();
        assertThat(duplicate).doesNotContain("uk_categories_scope_type_name", "scope_key",
                "Duplicate entry", "categories.", "user_id");

        String defaultClash = create(token, Map.of("name", DEFAULT_EXPENSE_NAME, "type", "EXPENSE"))
                .getBody();
        assertThat(defaultClash).doesNotContain("trg_categories", "BR-06", "SIGNAL");

        Long referenced = createAndReturnId(token, Map.of("name", "In Use", "type", "EXPENSE"));
        insertTransaction(userIdOfNewStudent(token), referenced, "log probe");

        String inUse = send(HttpMethod.PATCH, CATEGORIES_URL + "/" + referenced, token,
                Map.of("type", "INCOME")).getBody();
        assertThat(inUse).doesNotContain("trg_categories", "BR-05", "SIGNAL", "recurring_rules");
        // The message does say "a transaction, a budget or a recurring rule", which is the
        // business explanation and is meant to be there. What must not appear is the schema.
        assertThat(inUse).contains("transaction");
    }

    @Test
    @DisplayName("UC-06: simultaneous identical creates produce one row, not two and not a 500")
    void simultaneousIdenticalCreatesAreSerialisedByTheUniqueKey() throws Exception {
        // The service checks the name before inserting, so the pre-check catches the ordinary
        // duplicate. Two requests that both pass that check and then insert is the case it cannot
        // catch, and the only thing standing between the pair and two rows is the unique key.
        //
        // What has to hold: exactly one 201, every other attempt a 409 that names the field, no
        // 500 from an untranslated constraint violation, and one row. The guarantee is the end
        // state and the absence of a 500, which is what a client experiences. Whether the
        // constraint or the pre-check is what refused a given loser is deliberately not asserted:
        // the pre-check usually wins even with a barrier, and a test that demanded the constraint
        // would be flaky rather than more rigorous. The constraint branch itself is verified in
        // CategoryWriteFailureTest.
        String token = loginNewStudent();
        int attempts = 6;

        // A barrier so the requests are genuinely in flight together rather than merely submitted
        // in a loop, which would let the first one finish before the second starts.
        CyclicBarrier startTogether = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<ResponseEntity<String>>> results = new ArrayList<>();
            for (int attempt = 0; attempt < attempts; attempt++) {
                results.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return create(token, Map.of("name", "Raced", "type", "EXPENSE"));
                }));
            }

            int created = 0;
            for (Future<ResponseEntity<String>> result : results) {
                ResponseEntity<String> response = result.get(30, TimeUnit.SECONDS);
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    created++;
                    continue;
                }
                assertThat(response.getStatusCode())
                        .as("body=%s", response.getBody())
                        .isEqualTo(HttpStatus.CONFLICT);
                assertThat(objectMapper.readTree(response.getBody()).get("errorCode").asText())
                        .isEqualTo("CATEGORY_NAME_TAKEN");
            }
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(countOwnCategoriesWithName(token, "Raced")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Section 7.7: a malformed body is a 400 with no internals in the response")
    void malformedBodyIsRejectedWithoutLeakingInternals() throws Exception {
        String token = loginNewStudent();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        ResponseEntity<String> response = restTemplate.exchange(CATEGORIES_URL, HttpMethod.POST,
                new HttpEntity<>("{ this is not json", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String body = response.getBody();
        assertThat(body).doesNotContain("java.", "springframework", "hibernate", "SQLException",
                "at com.campuscoin", "stackTrace");
        assertThat(objectMapper.readTree(body).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("timestamp", "status", "errorCode", "message", "path");
    }

    @Test
    @DisplayName("UC-06: an unknown identifier is a 404")
    void unknownIdentifierIsNotFound() throws Exception {
        String token = loginNewStudent();

        assertThat(send(HttpMethod.GET, CATEGORIES_URL + "/99999999", token, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.PATCH, CATEGORIES_URL + "/99999999", token,
                Map.of("name", "Ghost")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.DELETE, CATEGORIES_URL + "/99999999", token, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("UC-06: an identifier that is not a number is a 400")
    void malformedIdentifierIsBadRequest() throws Exception {
        String token = loginNewStudent();

        // A path variable cannot carry a field-level error: there is no body field to name, and
        // the path is already in the response. The handler answers INVALID_REQUEST for that
        // reason, which is the contract module 1 established for a mistyped parameter.
        ResponseEntity<String> response =
                send(HttpMethod.GET, CATEGORIES_URL + "/not-a-number", token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode error = objectMapper.readTree(response.getBody());
        assertThat(error.get("errorCode").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(error.has("fieldErrors")).isFalse();

        // The same for an identifier that overflows the column, rather than a 500 on the way to
        // the database.
        assertThat(send(HttpMethod.GET, CATEGORIES_URL + "/99999999999999999999999", token, null)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private ResponseEntity<String> create(String token, Map<String, ?> body) {
        return send(HttpMethod.POST, CATEGORIES_URL, token, body);
    }

    private Long createAndReturnId(String token, Map<String, ?> body) throws Exception {
        ResponseEntity<String> response = create(token, body);
        assertThat(response.getStatusCode())
                .as("create body=%s", body)
                .isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody()).get("id").asLong();
    }

    private void assertFieldError(String token, Map<String, ?> body, String expectedField)
            throws Exception {
        assertFieldError(token, HttpMethod.POST, CATEGORIES_URL, body, expectedField);
    }

    private void assertFieldError(String token, HttpMethod method, String url, Object body,
                                  String expectedField) throws Exception {
        ResponseEntity<String> response = send(method, url, token, body);

        assertThat(response.getStatusCode())
                .as("body=%s", body)
                .isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode parsed = objectMapper.readTree(response.getBody());
        assertThat(parsed.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");

        List<String> names = new ArrayList<>();
        parsed.get("fieldErrors").forEach(error -> names.add(error.get("field").asText()));
        // `field` is the canonical property name project-wide (section 19). A `path` key here would
        // break the Angular error renderer.
        assertThat(names).as("body=%s", body).contains(expectedField);
    }

    /** Registers a student and returns a bearer token for them. */
    private String loginNewStudent() throws Exception {
        String email = randomEmail();
        register(email);
        return login(email);
    }

    private String register(String email) {
        ResponseEntity<String> response = send(HttpMethod.POST, REGISTER_URL, null, Map.of(
                "fullName", "Test Student",
                "email", email,
                "password", PASSWORD,
                "confirmPassword", PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return email;
    }

    private Long registerAndReturnId() throws Exception {
        String email = randomEmail();
        register(email);
        return userIdOf(email);
    }

    private String login(String email) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, LOGIN_URL, null,
                Map.of("email", email, "password", PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    private String adminLogin() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, ADMIN_LOGIN_URL, null,
                Map.of("email", SEEDED_ADMIN_EMAIL, "password", SEEDED_ADMIN_PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    /** The caller's own id, read from the profile endpoint rather than guessed. */
    private Long userIdOfNewStudent(String token) throws Exception {
        JsonNode profile = objectMapper.readTree(
                send(HttpMethod.GET, "/api/v1/profile/me", token, null).getBody());
        return profile.get("id").asLong();
    }

    private ResponseEntity<String> send(HttpMethod method, String url, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    // --- database access --------------------------------------------------------------------

    /**
     * Inserts a transaction referencing a category, and returns its id.
     *
     * <p>Written directly because {@code transactions} belongs to module 4. Only the fields the
     * schema requires are named; the trigger fills in the rest. The insert proves the reference is
     * real rather than a test-only shortcut: {@code sp_validate_transaction} runs on the way in and
     * accepts it.
     */
    private Long insertTransaction(Long userId, Long categoryId, String description)
            throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO transactions (user_id, category_id, amount, description, txn_date, "
                             + "source) VALUES (?, ?, ?, ?, ?, 'MANUAL')",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setLong(2, categoryId);
            statement.setBigDecimal(3, new BigDecimal("10.00"));
            statement.setString(4, description);
            statement.setObject(5, todayInApplicationZone());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    /**
     * Inserts a budget for the current month, referencing a category.
     *
     * <p>{@code trg_budgets_before_insert} runs {@code sp_validate_budget} here, which is why the
     * referencing tests use an EXPENSE category: a budget on an INCOME one is refused by BR-11.
     */
    private void insertBudget(Long userId, Long categoryId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO budgets (user_id, category_id, period_month, limit_amount) "
                             + "VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, userId);
            statement.setLong(2, categoryId);
            statement.setObject(3, todayInApplicationZone().withDayOfMonth(1));
            statement.setBigDecimal(4, new BigDecimal("100.00"));
            statement.executeUpdate();
        }
    }

    private boolean transactionExists(Long transactionId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM transactions WHERE id = ?")) {
            statement.setLong(1, transactionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    /** How many {@code transaction_history} rows the trigger wrote for this transaction. */
    private long historyRowsFor(Long transactionId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM transaction_history WHERE transaction_id = ?")) {
            statement.setLong(1, transactionId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    /**
     * Today in {@code Asia/Ho_Chi_Minh}, the zone the application writes dates in (VĐ-10).
     *
     * <p>A test helper must not assume the machine's zone: the container runs at {@code +07:00}, and
     * a date computed in a zone behind it could land on tomorrow inside {@code transactions}, where
     * {@code sp_validate_transaction} would refuse it as a future date for every source but
     * RECURRING.
     */
    private static LocalDate todayInApplicationZone() {
        return LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    /** The id of a seeded default category by name, used to prove students cannot touch them. */
    private Long defaultCategoryId(String name) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM categories WHERE user_id IS NULL AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private boolean rowExists(Long categoryId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM categories WHERE id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private String columnInDatabase(Long categoryId, String column) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM categories WHERE id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** Every column of a category row, so an unwanted change is visible rather than plausible. */
    private String rowSnapshot(Long categoryId) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT name, type, icon, color, description, sort_order, is_active, user_id "
                             + "FROM categories WHERE id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                StringBuilder snapshot = new StringBuilder();
                for (int index = 1; index <= 8; index++) {
                    snapshot.append(row.getString(index)).append('|');
                }
                return snapshot.toString();
            }
        }
    }

    private long countDefaults() throws Exception {
        try (Connection connection = openDatabaseConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT COUNT(*) FROM categories WHERE user_id IS NULL")) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }

    private Long countOwnCategoriesWithName(String token, String name) throws Exception {
        Long userId = userIdOfNewStudent(token);
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM categories WHERE user_id = ? AND name = ?")) {
            statement.setLong(1, userId);
            statement.setString(2, name);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private Long userIdOf(String email) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    /**
     * Sets {@code users.status} directly, as an administrator's disable would.
     *
     * <p>Written straight to the column because UC-22/UC-23 belong to module 11 and the procedure
     * that owns this change is not reachable yet. The status is what the token filter reads, so
     * setting the column exercises the same path the real operation will.
     */
    private void setAccountStatus(Long userId, String status) throws Exception {
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE users SET status = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setLong(2, userId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private String randomEmail() {
        return "category." + UUID.randomUUID() + "@student.campuscoin.edu";
    }
}
