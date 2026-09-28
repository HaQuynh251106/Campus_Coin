package com.campuscoin.categorisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

class CategorisationApiIT extends AbstractCategorisationApiIT {

    @Test
    @DisplayName("Campus Cafe: proposed, accepted, overridden, and learned from the override")
    void theAcceptanceScenario() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        Long food = defaultCategoryId(FOOD);
        Long transactionId = aRecord(token, CAMPUS_CAFE);
        assertThat(storedSuggestionOf(transactionId).suggestedCategoryId())
                .as("a freshly filed record carries no suggestion")
                .isNull();

        int historyAfterCreate = historyCountOf(transactionId);
        JsonNode first = suggestExpectingOk(token, transactionId);

        assertThat(first.get("source").asText()).isEqualTo("NONE");
        assertThat(first.has("categoryId")).as("source NONE carries no category").isFalse();
        assertThat(first.get("learned").get("keyword").asText()).isEqualTo("campus cafe");
        assertThat(first.get("learned").get("categoryId").asLong()).isEqualTo(food);
        assertThat(first.get("learned").get("categoryName").asText()).isEqualTo(FOOD);
        assertThat(first.get("learned").get("source").asText()).isEqualTo("ACCEPTED");
        assertThat(storedRuleCategoryNameOf(userId, "campus cafe")).isEqualTo(FOOD);

        assertThat(storedSuggestionOf(transactionId).suggestedCategoryId()).isNull();
        assertThat(historyCountOf(transactionId))
                .as("a request that proposes nothing writes nothing, so the log gains no row")
                .isEqualTo(historyAfterCreate);

        assertThat(storedCategoryIdOf(transactionId)).isEqualTo(food);

        JsonNode second = suggestExpectingOk(token, transactionId);

        assertThat(second.get("source").asText()).isEqualTo("RULE");
        assertThat(second.get("categoryId").asLong()).isEqualTo(food);
        assertThat(second.get("categoryName").asText()).isEqualTo(FOOD);
        assertThat(second.get("type").asText()).isEqualTo("EXPENSE");
        assertThat(second.get("confidence").decimalValue()).isEqualByComparingTo("1.0000");
        assertThat(second.has("reason")).as("a rule has nothing to say beyond its source").isFalse();
        assertThat(second.get("learned").get("source").asText())
                .as("the student kept the proposal, so the filing corrected nothing")
                .isEqualTo("ACCEPTED");

        assertThat(storedSuggestionOf(transactionId).suggestedCategoryId()).isEqualTo(food);
        assertThat(storedSuggestionOf(transactionId).overridden()).isFalse();
        assertThat(storedRuleCountOf(userId, "campus cafe"))
                .as("the unique key keeps one row per keyword")
                .isEqualTo(1);

        int historyAfterFirstWrite = historyCountOf(transactionId);
        assertThat(historyAfterFirstWrite).isGreaterThan(historyAfterCreate);
        JsonNode third = suggestExpectingOk(token, transactionId);

        assertThat(third.get("source").asText()).isEqualTo("RULE");
        assertThat(historyCountOf(transactionId)).isEqualTo(historyAfterFirstWrite);
        assertThat(categorisationHistoryFieldsOf(transactionId)).contains("aiSuggestedCategoryId");

        Long transport = defaultCategoryId(TRANSPORT);
        moveToCategoryExpectingOk(token, transactionId, transport);
        int historyAfterMove = historyCountOf(transactionId);

        JsonNode fourth = suggestExpectingOk(token, transactionId);

        assertThat(fourth.get("source").asText()).isEqualTo("RULE");
        assertThat(fourth.get("categoryId").asLong()).isEqualTo(food);
        assertThat(fourth.get("categoryName").asText()).isEqualTo(FOOD);

        assertThat(storedSuggestionOf(transactionId).suggestedCategoryId()).isEqualTo(food);
        assertThat(storedSuggestionOf(transactionId).overridden()).isTrue();
        assertThat(fourth.get("learned").get("source").asText()).isEqualTo("OVERRIDE");
        assertThat(fourth.get("learned").get("categoryId").asLong()).isEqualTo(transport);
        assertThat(fourth.get("learned").get("categoryName").asText()).isEqualTo(TRANSPORT);
        assertThat(storedRuleCategoryNameOf(userId, "campus cafe")).isEqualTo(TRANSPORT);

        assertThat(historyAfterMove).isEqualTo(historyAfterFirstWrite + 1);
        assertThat(historyCountOf(transactionId))
                .as("the correction is one more write, and it is recorded")
                .isEqualTo(historyAfterMove + 1);

        assertThat(storedCategoryIdOf(transactionId)).isEqualTo(transport);
    }

    @Test
    @DisplayName("A learned mapping answers the next record with the same description")
    void aLearnedMappingAnswersTheNextRecord() throws Exception {
        String token = loginNewStudent();

        Long first = aRecord(token, CAMPUS_CAFE);
        suggestExpectingOk(token, first);

        Long second = aRecord(token, "  Campus Cafe  ");
        JsonNode response = suggestExpectingOk(token, second);

        assertThat(response.get("source").asText()).isEqualTo("RULE");
        assertThat(response.get("categoryId").asLong()).isEqualTo(defaultCategoryId(FOOD));
        assertThat(response.get("learned").get("keyword").asText())
                .as("the keyword is stored normalised, so padding taught nothing new")
                .isEqualTo("campus cafe");
    }

    @Test
    @DisplayName("A different description is not matched by a mapping for another one")
    void adifferentDescriptionIsNotMatched() throws Exception {
        String token = loginNewStudent();

        suggestExpectingOk(token, aRecord(token, CAMPUS_CAFE));
        Long other = aRecord(token, "Bus ticket to Hanoi");

        JsonNode response = suggestExpectingOk(token, other);

        assertThat(response.get("source").asText()).isEqualTo("NONE");
        assertThat(response.get("learned").get("keyword").asText()).isEqualTo("bus ticket to hanoi");
    }

    @Test
    @DisplayName("The suggestion, the confidence and the verdict are all recorded on the record")
    void theProposalIsStoredBesideTheRecord() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aRecord(token, CAMPUS_CAFE);
        suggestExpectingOk(token, transactionId);

        Long transport = defaultCategoryId(TRANSPORT);
        moveToCategoryExpectingOk(token, transactionId, transport);
        suggestExpectingOk(token, transactionId);

        StoredSuggestion stored = storedSuggestionOf(transactionId);
        assertThat(stored.suggestedCategoryId()).isEqualTo(defaultCategoryId(FOOD));
        assertThat(stored.confidence()).isEqualByComparingTo("1.0000");
        assertThat(stored.overridden()).isTrue();

        String fields = categorisationHistoryFieldsOf(transactionId);
        assertThat(fields).isNotNull();
        assertThat(fields).contains("aiSuggestedCategoryId");
        assertThat(fields).contains("aiConfidence");
        assertThat(fields).contains("aiOverridden");
    }

    @Test
    @DisplayName("A record with no description is proposed nothing and teaches nothing")
    void aRecordWithNoDescriptionIsAnswered() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long transactionId = createTransaction(token, defaultCategoryId(FOOD), "25000", today(), null);

        JsonNode response = suggestExpectingOk(token, transactionId);

        assertThat(response.get("source").asText()).isEqualTo("NONE");
        assertThat(response.has("learned")).as("there was no text to learn from").isFalse();
        assertThat(countOf("SELECT COUNT(*) FROM category_rules WHERE user_id = ?", userId))
                .as("nothing was written to the rules table")
                .isZero();
        assertThat(storedSuggestionOf(transactionId).suggestedCategoryId())
                .as("nothing was proposed, so nothing is stored")
                .isNull();
    }

    @Test
    @DisplayName("A blank description is treated as no description")
    void aBlankDescriptionIsTreatedAsNone() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long transactionId = aRecord(token, "   ");

        JsonNode response = suggestExpectingOk(token, transactionId);

        assertThat(response.get("source").asText()).isEqualTo("NONE");
        assertThat(response.has("learned")).isFalse();
        assertThat(countOf("SELECT COUNT(*) FROM category_rules WHERE user_id = ?", userId)).isZero();
    }

    @Test
    @DisplayName("A description longer than the keyword column is suggested but not learned")
    void anOverlongDescriptionIsNotLearned() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        String overlong = "lunch with the study group at the campus cafe near the library every "
                + "tuesday after the seminar";

        assertThat(overlong.length()).isGreaterThan(80);

        Long transactionId = aRecord(token, overlong);
        JsonNode response = suggestExpectingOk(token, transactionId);

        assertThat(response.get("source").asText()).isEqualTo("NONE");
        assertThat(response.has("learned"))
                .as("the column cannot hold it, and a truncation would match nothing")
                .isFalse();
        assertThat(countOf("SELECT COUNT(*) FROM category_rules WHERE user_id = ?", userId)).isZero();
    }

    @Test
    @DisplayName("The response carries the student's own words back and no ciphertext")
    void theResponseCarriesNoCiphertext() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aRecord(token, CAMPUS_CAFE);
        suggestExpectingOk(token, transactionId);

        JsonNode response = body(suggest(token, transactionId));

        assertThat(response.toString()).doesNotContain("v1:");
        assertThat(response.toString()).contains("campus cafe");
    }

    @Test
    @DisplayName("Another student's record is not found, and not told apart from an absent one")
    void anotherStudentsRecordIsNotFound() throws Exception {
        String owner = loginNewStudent();
        Long transactionId = aRecord(owner, CAMPUS_CAFE);

        String stranger = loginNewStudent();
        ResponseEntity<String> strangerResponse = suggest(stranger, transactionId);
        ResponseEntity<String> absentResponse = suggest(stranger, 999_999_999L);

        assertThat(strangerResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(absentResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(withoutTimestamp(body(strangerResponse)))
                .as("'not yours' and 'not there' must be one answer")
                .isEqualTo(withoutTimestamp(body(absentResponse)));

        assertThat(storedSuggestionOf(transactionId).suggestedCategoryId()).isNull();
    }

    @Test
    @DisplayName("A trashed record is not categorised")
    void aTrashedRecordIsNotFound() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aRecord(token, CAMPUS_CAFE);
        trash(token, transactionId);

        ResponseEntity<String> response = suggest(token, transactionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCodeOf(response)).isEqualTo("NOT_FOUND");

        assertThat(storedSuggestionOf(transactionId).suggestedCategoryId()).isNull();
    }

    @Test
    @DisplayName("A body with no transaction id is refused as a field error")
    void aMissingTransactionIdIsAValidationError() throws Exception {
        String token = loginNewStudent();

        ResponseEntity<String> response = send(
                org.springframework.http.HttpMethod.POST, SUGGEST_URL, token, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(response).get("fieldErrors").toString()).contains("transactionId");
    }

    @Test
    @DisplayName("No token is unauthenticated")
    void noTokenIsUnauthenticated() throws Exception {
        ResponseEntity<String> response =
                send(org.springframework.http.HttpMethod.POST, SUGGEST_URL, null,
                        Map.of("transactionId", 1L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("An administrator's token is refused by the student role rule")
    void anAdminTokenIsForbidden() throws Exception {

        ResponseEntity<String> response = suggest(adminLogin(), 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("The response carries exactly the documented fields, and no others")
    void theResponseHasExactlyTheDocumentedFields() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aRecord(token, CAMPUS_CAFE);

        suggestExpectingOk(token, transactionId);
        moveToCategoryExpectingOk(token, transactionId, defaultCategoryId(TRANSPORT));
        JsonNode response = suggestExpectingOk(token, transactionId);

        assertThat(response.get("source").asText()).isEqualTo("RULE");

        List<String> expectedForARule = new java.util.ArrayList<>(DOCUMENTED_RESPONSE_FIELDS);
        expectedForARule.remove("reason");

        java.util.List<String> names = new java.util.ArrayList<>();
        response.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrderElementsOf(expectedForARule);

        java.util.List<String> learnedNames = new java.util.ArrayList<>();
        response.get("learned").fieldNames().forEachRemaining(learnedNames::add);
        assertThat(learnedNames).containsExactlyInAnyOrderElementsOf(DOCUMENTED_LEARNED_FIELDS);

        assertThat(response.toString()).doesNotContain("userId");
        assertThat(response.toString()).doesNotContain("amount");
        assertThat(response.toString()).doesNotContain("passwordHash");
        assertThat(response.toString()).doesNotContain("tokenVersion");
    }

    @Test
    @DisplayName("The documented field set is what an AI proposal would carry too")
    void theDocumentedFieldSetCoversAProviderProposal() throws Exception {

        com.campuscoin.categorisation.dto.CategorySuggestionResponse full =
                new com.campuscoin.categorisation.dto.CategorySuggestionResponse(
                        31L,
                        com.campuscoin.categorisation.entity.SuggestionSource.AI,
                        4L, "Food", com.campuscoin.category.entity.CategoryType.EXPENSE,
                        new BigDecimal("0.8000"), "looks like a cafe purchase",
                        new com.campuscoin.categorisation.dto.LearnedCategoryRuleResponse(
                                "campus cafe", 4L, "Food",
                                com.campuscoin.categorisation.entity.RuleSource.ACCEPTED));

        JsonNode serialised = objectMapper.valueToTree(full);

        java.util.List<String> names = new java.util.ArrayList<>();
        serialised.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrderElementsOf(DOCUMENTED_RESPONSE_FIELDS);

        java.util.List<String> learnedNames = new java.util.ArrayList<>();
        serialised.get("learned").fieldNames().forEachRemaining(learnedNames::add);
        assertThat(learnedNames).containsExactlyInAnyOrderElementsOf(DOCUMENTED_LEARNED_FIELDS);
    }

    @Test
    @DisplayName("An absent proposal omits the category fields rather than emitting nulls")
    void anAbsentProposalOmitsItsFields() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aRecord(token, CAMPUS_CAFE);
        suggestExpectingOk(token, transactionId);

        Long unanswered = aRecord(token, "Roof repair on the hostel");
        JsonNode response = suggestExpectingOk(token, unanswered);

        assertThat(response.get("source").asText()).isEqualTo("NONE");
        assertThat(response.get("transactionId").asLong()).isEqualTo(unanswered);
        for (String omitted : List.of("categoryId", "categoryName", "type", "confidence", "reason")) {
            assertThat(response.has(omitted)).as("%s is omitted", omitted).isFalse();
        }
    }

    @Test
    @DisplayName("A rule proposal carries no reason, and a stored proposal carries the confidence")
    void aRuleProposalCarriesNoReason() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aRecord(token, CAMPUS_CAFE);
        suggestExpectingOk(token, transactionId);

        JsonNode response = suggestExpectingOk(token, transactionId);

        assertThat(response.get("source").asText()).isEqualTo("RULE");
        assertThat(response.has("reason"))
                .as("a rule's reason is the source itself; only a provider supplies prose")
                .isFalse();
        assertThat(response.get("type").asText()).isEqualTo("EXPENSE");
    }

    @Test
    @DisplayName("A record whose filing corrected the system keeps its own category (BR-13)")
    void theRecordIsNeverFiledByTheEndpoint() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aRecord(token, CAMPUS_CAFE);
        suggestExpectingOk(token, transactionId);

        Long transport = defaultCategoryId(TRANSPORT);
        moveToCategoryExpectingOk(token, transactionId, transport);
        Long historyBefore = (long) historyCountOf(transactionId);

        JsonNode response = suggestExpectingOk(token, transactionId);

        assertThat(response.get("categoryId").asLong()).isEqualTo(defaultCategoryId(FOOD));
        assertThat(storedCategoryIdOf(transactionId))
                .as("the student's filing decides where the money is counted")
                .isEqualTo(transport);

        assertThat(historyCountOf(transactionId)).isEqualTo(historyBefore + 1);
        assertThat(categorisationHistoryFieldsOf(transactionId)).doesNotContain("categoryId");
    }

    @Test
    @DisplayName("A student's own categories are what a mapping may point at")
    void aPersonalCategoryCanBeTaught() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        Long personal = createPersonalCategory(token, "Campus Food");

        Long transactionId = aRecordInCategory(token, personal, "Coffee at the library");
        JsonNode response = suggestExpectingOk(token, transactionId);

        assertThat(response.get("learned").get("categoryId").asLong()).isEqualTo(personal);
        assertThat(storedRuleCategoryNameOf(userId, "coffee at the library"))
                .isEqualTo("Campus Food");
    }

    @Test
    @DisplayName("A rule pointing at a category the student retired is not proposed")
    void aRetiredCategoryIsNotProposed() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        Long personal = createPersonalCategory(token, "Late Night Snacks");
        Long transactionId = aRecordInCategory(token, personal, CAMPUS_CAFE);
        suggestExpectingOk(token, transactionId);
        assertThat(storedRuleCategoryNameOf(userId, "campus cafe")).isEqualTo("Late Night Snacks");

        ResponseEntity<String> retired = send(org.springframework.http.HttpMethod.PATCH,
                "/api/v1/categories/" + personal, token, Map.of("isActive", false));
        assertThat(retired.getStatusCode())
                .as("retire body=%s", retired.getBody())
                .isEqualTo(HttpStatus.OK);

        Long another = aRecordIn(token, FOOD, CAMPUS_CAFE);
        JsonNode response = suggestExpectingOk(token, another);

        assertThat(response.get("source").asText())
                .as("filing new records there is refused, so proposing it would be offering an action "
                        + "the database would reject")
                .isEqualTo("NONE");
        assertThat(response.has("categoryId")).isFalse();

        assertThat(response.get("learned").get("categoryId").asLong())
                .isEqualTo(defaultCategoryId(FOOD));
        assertThat(storedRuleCategoryNameOf(userId, "campus cafe")).isEqualTo(FOOD);
        assertThat(storedRuleCountOf(userId, "campus cafe"))
                .as("re-taught in place, not appended")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Two calls on an unchanged record leave exactly one suggestion history row")
    void repeatedCallsDoNotAccumulateHistory() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aRecord(token, CAMPUS_CAFE);

        suggestExpectingOk(token, transactionId);
        suggestExpectingOk(token, transactionId);

        int baseline = historyCountOf(transactionId);
        StoredSuggestion stored = storedSuggestionOf(transactionId);
        for (int attempt = 0; attempt < 4; attempt++) {
            suggestExpectingOk(token, transactionId);
        }

        assertThat(historyCountOf(transactionId)).isEqualTo(baseline);
        assertThat(storedSuggestionOf(transactionId))
                .as("the stored triple never moved")
                .isEqualTo(stored);
    }

    @Test
    @DisplayName("A student only ever sees mappings they taught themselves")
    void oneStudentsMappingIsInvisibleToAnother() throws Exception {
        String first = loginNewStudent();
        suggestExpectingOk(first, aRecord(first, CAMPUS_CAFE));

        String second = loginNewStudent();
        Long transactionId = aRecord(second, CAMPUS_CAFE);
        JsonNode response = suggestExpectingOk(second, transactionId);

        assertThat(response.get("source").asText())
                .as("the mapping is per student, and a fresh account has none")
                .isEqualTo("NONE");
        assertThat(response.get("learned").get("source").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("The stored confidence is a DECIMAL the column accepts, not a double")
    void theStoredConfidenceMatchesTheColumn() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aRecord(token, CAMPUS_CAFE);
        suggestExpectingOk(token, transactionId);
        moveToCategoryExpectingOk(token, transactionId, defaultCategoryId(TRANSPORT));
        suggestExpectingOk(token, transactionId);

        BigDecimal confidence = storedSuggestionOf(transactionId).confidence();

        assertThat(confidence).isEqualByComparingTo("1.0000");
        assertThat(confidence.scale()).isLessThanOrEqualTo(4);
    }
}
