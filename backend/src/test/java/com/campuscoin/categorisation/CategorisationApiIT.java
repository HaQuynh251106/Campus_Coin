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

/**
 * UC-08 — categorisation and learning, end to end over HTTP.
 *
 * <p>Everything here runs against a real MySQL with the project's own triggers loaded, because three of
 * the behaviours under test are the database's rather than the Java's. The suggestion columns are written
 * by a statement the trigger then validates and records in {@code transaction_history}; the mapping is
 * upserted through {@code uk_rule_user_keyword}; and the filing that must not change is the record's own
 * {@code category_id}. A mocked repository would let every assertion here pass while a trigger said
 * something else.
 *
 * <p><b>The acceptance scenario runs first and in one test.</b> UC-08's stated walk-through is
 * Campus Cafe &rarr; Food &rarr; an override &rarr; learning, and its steps are only meaningful in
 * sequence - the second call is a no-op only because the first stored the suggestion, and the third is an
 * override only because the second taught the mapping. Split across tests they would each have to
 * reconstruct the earlier ones, which is how a suite ends up asserting a fixture rather than the feature.
 *
 * <p><b>No provider is configured in this suite, and that is the point.</b> There is no AI credential in
 * the test environment, so {@code AiConfig} installs the no-op port and every answer here comes from the
 * student's own mappings. That is the deployment the feature has to work in, and it is the one whose
 * behaviour a reviewer can reproduce without a key. What a provider would add is exercised by
 * {@code CategorySuggesterTest}, which can hand the suggester an answer without a network.
 *
 * <p>What this class is trying to break:
 *
 * <ul>
 *   <li>that the endpoint files the record - it must propose and never move the money (BR-13);</li>
 *   <li>that a repeated call accumulates {@code transaction_history} rows BR-09 keeps;</li>
 *   <li>that the mapping learned is the proposed category rather than the one the student filed under,
 *       which would make an override teach the system the thing the student rejected;</li>
 *   <li>that the response names a category the mapping does not point at;</li>
 *   <li>that another student's record, or a trashed one, is distinguishable from an absent one;</li>
 *   <li>that the path is behind the authenticated catch-all rather than the student role rule.</li>
 * </ul>
 */
class CategorisationApiIT extends AbstractCategorisationApiIT {

    // ==================================================================
    //  The acceptance scenario
    // ==================================================================

    @Test
    @DisplayName("Campus Cafe: proposed, accepted, overridden, and learned from the override")
    void theAcceptanceScenario() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        // Step 1 - the student files "Campus Cafe" under Food, which is where they always file it.
        Long food = defaultCategoryId(FOOD);
        Long transactionId = aRecord(token, CAMPUS_CAFE);
        assertThat(storedSuggestionOf(transactionId).suggestedCategoryId())
                .as("a freshly filed record carries no suggestion")
                .isNull();

        // Step 2 - the first ask teaches the mapping and proposes nothing. There is no mapping yet and no
        // provider, so the answer is NONE; but the mapping is stored anyway, because the filing is where
        // the student put it. Were learning conditional on a proposal, this deployment - the one with no
        // credential - could never create a rule and the RULE path would be unreachable through the API.
        int historyAfterCreate = historyCountOf(transactionId);
        JsonNode first = suggestExpectingOk(token, transactionId);

        assertThat(first.get("source").asText()).isEqualTo("NONE");
        assertThat(first.has("categoryId")).as("source NONE carries no category").isFalse();
        assertThat(first.get("learned").get("keyword").asText()).isEqualTo("campus cafe");
        assertThat(first.get("learned").get("categoryId").asLong()).isEqualTo(food);
        assertThat(first.get("learned").get("categoryName").asText()).isEqualTo(FOOD);
        assertThat(first.get("learned").get("source").asText()).isEqualTo("ACCEPTED");
        assertThat(storedRuleCategoryNameOf(userId, "campus cafe")).isEqualTo(FOOD);

        // Nothing was proposed, so the triple the record already carried - three nulls - is what it still
        // carries, and the statement that would have written it never ran.
        assertThat(storedSuggestionOf(transactionId).suggestedCategoryId()).isNull();
        assertThat(historyCountOf(transactionId))
                .as("a request that proposes nothing writes nothing, so the log gains no row")
                .isEqualTo(historyAfterCreate);

        // The record is filed where it was filed. The endpoint wrote a note beside it, not over it.
        assertThat(storedCategoryIdOf(transactionId)).isEqualTo(food);

        // Step 3 - the second ask is answered by the mapping the first one taught. This is the whole of
        // the RULE path in a deployment with no AI service, and it is the first call that has something
        // to store, so it is this one that records the triple and appends the history row.
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

        // Step 4 - the same ask a third time is a read. The triple is unchanged, so the record is not
        // written, so the trigger appends nothing: the history stays an account of what happened rather
        // than of how often the screen was opened.
        int historyAfterFirstWrite = historyCountOf(transactionId);
        assertThat(historyAfterFirstWrite).isGreaterThan(historyAfterCreate);
        JsonNode third = suggestExpectingOk(token, transactionId);

        assertThat(third.get("source").asText()).isEqualTo("RULE");
        assertThat(historyCountOf(transactionId)).isEqualTo(historyAfterFirstWrite);
        assertThat(categorisationHistoryFieldsOf(transactionId)).contains("aiSuggestedCategoryId");

        // Step 5 - the student moves the record to Transport. Now the system has a mapping for
        // "campus cafe" pointing at Food and the record says otherwise: this is the correction B6 is
        // about, and the point of the whole use case.
        Long transport = defaultCategoryId(TRANSPORT);
        moveToCategoryExpectingOk(token, transactionId, transport);
        int historyAfterMove = historyCountOf(transactionId);

        JsonNode fourth = suggestExpectingOk(token, transactionId);

        // The mapping answers, and it answers with where it pointed before the correction. That is what
        // makes this a correction rather than a re-guess: the student is shown what the system believed
        // so that they can disagree with it.
        assertThat(fourth.get("source").asText()).isEqualTo("RULE");
        assertThat(fourth.get("categoryId").asLong()).isEqualTo(food);
        assertThat(fourth.get("categoryName").asText()).isEqualTo(FOOD);

        // And it records both the proposal and the verdict, and learns the corrected mapping.
        assertThat(storedSuggestionOf(transactionId).suggestedCategoryId()).isEqualTo(food);
        assertThat(storedSuggestionOf(transactionId).overridden()).isTrue();
        assertThat(fourth.get("learned").get("source").asText()).isEqualTo("OVERRIDE");
        assertThat(fourth.get("learned").get("categoryId").asLong()).isEqualTo(transport);
        assertThat(fourth.get("learned").get("categoryName").asText()).isEqualTo(TRANSPORT);
        assertThat(storedRuleCategoryNameOf(userId, "campus cafe")).isEqualTo(TRANSPORT);
        // The move wrote its own history row - it is a category change, and BR-09 keeps that too - so the
        // baseline for the correction is what the count stood at after the move, not before it.
        assertThat(historyAfterMove).isEqualTo(historyAfterFirstWrite + 1);
        assertThat(historyCountOf(transactionId))
                .as("the correction is one more write, and it is recorded")
                .isEqualTo(historyAfterMove + 1);

        // The correction moved the mapping, not the record - the student moved that, and only they can.
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

    // ==================================================================
    //  What is written beside the record
    // ==================================================================

    @Test
    @DisplayName("The suggestion, the confidence and the verdict are all recorded on the record")
    void theProposalIsStoredBesideTheRecord() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aRecord(token, CAMPUS_CAFE);
        suggestExpectingOk(token, transactionId);

        // Now the record is moved away and asked again, so there is a real proposal to store.
        Long transport = defaultCategoryId(TRANSPORT);
        moveToCategoryExpectingOk(token, transactionId, transport);
        suggestExpectingOk(token, transactionId);

        StoredSuggestion stored = storedSuggestionOf(transactionId);
        assertThat(stored.suggestedCategoryId()).isEqualTo(defaultCategoryId(FOOD));
        assertThat(stored.confidence()).isEqualByComparingTo("1.0000");
        assertThat(stored.overridden()).isTrue();

        // And the trigger recorded the move, naming the columns - which is the evidence that the write
        // happened at all, and which a no-change call must not produce.
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

    // ==================================================================
    //  The description is read, never published
    // ==================================================================

    @Test
    @DisplayName("The response carries the student's own words back and no ciphertext")
    void theResponseCarriesNoCiphertext() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aRecord(token, CAMPUS_CAFE);
        suggestExpectingOk(token, transactionId);

        JsonNode response = body(suggest(token, transactionId));

        // The whole of the response, as a string. The stored description is an AES-256-GCM envelope with
        // a random IV, so it cannot appear here even by accident - but the learned keyword is the
        // student's own text, which is theirs to see.
        assertThat(response.toString()).doesNotContain("v1:");
        assertThat(response.toString()).contains("campus cafe");
    }

    // ==================================================================
    //  Ownership and refusals
    // ==================================================================

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

        // Everything but the timestamp, which necessarily differs between two calls. An identical body
        // would be identical for a stranger's record and for one that does not exist, which is what keeps
        // a caller from enumerating other students' record identifiers (section 7.5).
        assertThat(withoutTimestamp(body(strangerResponse)))
                .as("'not yours' and 'not there' must be one answer")
                .isEqualTo(withoutTimestamp(body(absentResponse)));

        // And nothing was written for the stranger: the read narrowed before any write ran.
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
        // A suggestion must not outlive the record it describes.
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
        // The route is under /api/v1/ai, which nothing else claims, so it is the role rule that decides
        // this rather than the administrative prefix. An administrator's own account has no records to
        // categorise, and allowing one here would let an administrator's token read a student's rows
        // through a route written for students.
        ResponseEntity<String> response = suggest(adminLogin(), 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ACCESS_DENIED");
    }

    // ==================================================================
    //  The published contract
    // ==================================================================

    @Test
    @DisplayName("The response carries exactly the documented fields, and no others")
    void theResponseHasExactlyTheDocumentedFields() throws Exception {
        String token = loginNewStudent();
        Long transactionId = aRecord(token, CAMPUS_CAFE);

        // A RULE proposal, which is the fullest shape the response takes: a provider could only add a
        // reason, and the field is asserted absent here rather than required - see the test below.
        suggestExpectingOk(token, transactionId);
        moveToCategoryExpectingOk(token, transactionId, defaultCategoryId(TRANSPORT));
        JsonNode response = suggestExpectingOk(token, transactionId);

        assertThat(response.get("source").asText()).isEqualTo("RULE");

        // Every documented field except the one only a provider populates. The full set is asserted by
        // the serialisation test below, which can build a provider's answer without a credential.
        List<String> expectedForARule = new java.util.ArrayList<>(DOCUMENTED_RESPONSE_FIELDS);
        expectedForARule.remove("reason");

        java.util.List<String> names = new java.util.ArrayList<>();
        response.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrderElementsOf(expectedForARule);

        java.util.List<String> learnedNames = new java.util.ArrayList<>();
        response.get("learned").fieldNames().forEachRemaining(learnedNames::add);
        assertThat(learnedNames).containsExactlyInAnyOrderElementsOf(DOCUMENTED_LEARNED_FIELDS);

        // Nothing about the account, and nothing about the student's money.
        assertThat(response.toString()).doesNotContain("userId");
        assertThat(response.toString()).doesNotContain("amount");
        assertThat(response.toString()).doesNotContain("passwordHash");
        assertThat(response.toString()).doesNotContain("tokenVersion");
    }

    @Test
    @DisplayName("The documented field set is what an AI proposal would carry too")
    void theDocumentedFieldSetCoversAProviderProposal() throws Exception {
        // The shape is fixed by the schema suites and by the assertion above, but neither of those can
        // produce a provider's answer - there is no credential in this environment. What is asserted here
        // is the part that can be: the one field only a provider populates is in the documented list, so
        // the contract the OpenAPI document publishes is the contract the class under test implements.
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

        // The second call has a mapping, so to get NONE the description has to be one nobody taught.
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

        // The system believes Food; the record says Transport. Calling the endpoint adds a note and
        // must not resolve the disagreement itself.
        JsonNode response = suggestExpectingOk(token, transactionId);

        assertThat(response.get("categoryId").asLong()).isEqualTo(defaultCategoryId(FOOD));
        assertThat(storedCategoryIdOf(transactionId))
                .as("the student's filing decides where the money is counted")
                .isEqualTo(transport);

        // Exactly one write happened - the suggestion - and its history row names only suggestion
        // columns, so no statement touched category_id.
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

        // A category of the student's own, because retiring a shared default is the administrator's
        // (BR-06) and this test is about the student's own retirement.
        Long personal = createPersonalCategory(token, "Late Night Snacks");
        Long transactionId = aRecordInCategory(token, personal, CAMPUS_CAFE);
        suggestExpectingOk(token, transactionId);
        assertThat(storedRuleCategoryNameOf(userId, "campus cafe")).isEqualTo("Late Night Snacks");

        // Retire it (BR-07). The rule row survives - nothing deletes it - so the only thing that can keep
        // it from answering is the active-category list the suggester resolves against.
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

        // The mapping is then re-taught from this filing, which is the ordinary behaviour rather than an
        // exception to it: every call that has a description to learn from stores where the record sits,
        // and this record sits in Food. So the retired category is not merely not proposed - it stops
        // being reachable through this student's mappings at all.
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

        // The first ask teaches the mapping; the second is the first with something to record.
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

        // DECIMAL(5,4) with a CHECK bounding it to [0,1]. A value that failed either would have been
        // refused by ck_txn_ai_conf and answered as a 409, so reaching this line at all is half the
        // assertion; the other half is that it is the certain value the source implies.
        assertThat(confidence).isEqualByComparingTo("1.0000");
        assertThat(confidence.scale()).isLessThanOrEqualTo(4);
    }
}
