package com.campuscoin.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * UC-11 end to end: upload a file, read the preview, correct a row, commit or abandon it.
 *
 * <p><b>This is an integration suite rather than a unit suite, and it is the only one UC-11 can have
 * for these cases.</b> The commit is {@code sp_apply_csv_batch}'s whole body: the student's override is
 * authoritative there, the file's own category name is resolved only when there is none, the type's
 * default is the last resort, and a row the database refuses becomes an error row rather than ending
 * the walk. Every assertion below about what a commit produced is therefore an assertion about a
 * stored procedure and the triggers it fires - none of which a plain unit test can reach. The
 * arithmetic that <em>is</em> pure lives in {@code imports/service/} instead, where a boundary such as
 * "a date exactly the window's width apart" is one line rather than a sequence of dated records.
 *
 * <p><b>What each test is allowed to depend on.</b> Every test registers a fresh student with a random
 * address, so no test sees another's batches, rows, records or learned rules. The seeded accounts are
 * read (the administrator signs in, to prove the role rule) but never modified. No test depends on the
 * order the suite runs in.
 */
class ImportApiIT extends AbstractImportApiIT {

    // ==================================================================
    //  The published contract
    // ==================================================================

    @Test
    @DisplayName("UC-11: the preview, its rows and the list carry exactly the documented fields")
    void theResponsesCarryExactlyTheDocumentedFields() throws Exception {
        String token = loginNewStudent();

        // A file with one row of each verdict, so every branch of the row shape is present at once: an
        // importable row, a row the reader could not parse, and a row the student already has.
        anExistingRecord(token, FOOD, "12.50", EARLIER, "Campus Cafe");
        JsonNode batch = uploadExpectingCreated(token, csv(
                rowOf(LATER, "12.50", "EXPENSE", "Campus Cafe", FOOD),          // duplicate of that
                rowOf(LATER, "30.00", "EXPENSE", "Textbook", TRANSPORT),        // importable
                rowOf(LATER, "not a number", "EXPENSE", "Broken", FOOD)));      // unreadable

        assertThat(fieldNamesOf(batch)).containsExactlyInAnyOrderElementsOf(DOCUMENTED_BATCH_FIELDS);
        assertThat(batch.get("rows")).hasSize(3);
        for (JsonNode row : batch.get("rows")) {
            assertThat(fieldNamesOf(row))
                    .as("row %s", row.get("csvRowNo"))
                    .containsExactlyInAnyOrderElementsOf(DOCUMENTED_ROW_FIELDS);
        }

        JsonNode list = list(token);
        assertThat(fieldNamesOf(list)).containsExactlyInAnyOrderElementsOf(DOCUMENTED_LIST_FIELDS);
        assertThat(list.get("entries")).hasSize(1);
        assertThat(fieldNamesOf(list.get("entries").get(0)))
                .as("the nested-only summary shape, which the contract test cannot reach")
                .containsExactlyInAnyOrderElementsOf(DOCUMENTED_SUMMARY_FIELDS);

        // The list is a history, not the preview: it carries counters and no rows.
        assertThat(list.get("entries").get(0).has("rows")).isFalse();
    }

    // ==================================================================
    //  The acceptance scenario
    // ==================================================================

    @Test
    @DisplayName("UC-11: an invalid row is identifiable, the valid row imports, and nothing is lost")
    void invalidRowsAreIdentifiableAndValidRowsAreImportable() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        String amountMessage = "Amount must be a positive number, with at most 2 decimal places.";

        // One file, three rows: the middle one cannot be read, the two around it can. The point of the
        // file is that the bad row is a row and not a refusal - a bank export with one malformed line
        // is still worth importing, and the preview has to say which line to fix.
        JsonNode preview = uploadExpectingCreated(token, csv(
                rowOf(EARLIER, "12.50", "EXPENSE", "Campus Cafe", FOOD),
                rowOf(LATER, "-5.00", "EXPENSE", "Refund confusion", FOOD),
                rowOf(LATER, "45.00", "EXPENSE", "Textbook", "")));

        // Nothing is imported by the upload, and the batch says so.
        assertThat(preview.get("status").asText()).isEqualTo("PREVIEWED");
        assertThat(preview.get("modifiable").asBoolean()).isTrue();
        assertThat(preview.get("importedRows").asInt()).isZero();
        assertThat(liveTransactionCountOf(userId)).isZero();

        // The counters agree with the rows, and the bad row carries a sentence about the value rather
        // than about a parser.
        assertThat(preview.get("totalRows").asInt()).isEqualTo(3);
        assertThat(preview.get("validRows").asInt()).isEqualTo(2);
        assertThat(preview.get("errorRows").asInt()).isEqualTo(1);
        assertThat(preview.get("duplicateRows").asInt()).isZero();

        assertThat(rowStatusAt(preview, 2)).isEqualTo("VALID");
        assertThat(rowStatusAt(preview, 3)).isEqualTo("ERROR");
        assertThat(rowStatusAt(preview, 4)).isEqualTo("VALID");
        assertThat(errorMessageAt(preview, 3)).isEqualTo(amountMessage);

        // The row is still readable where it was readable: the reader reports the amount's problem and
        // keeps everything else, so the preview is not a row of blanks beside an explanation.
        assertThat(rowAt(preview, 3).get("parsedDate").asText()).isEqualTo(LATER.toString());
        assertThat(rowAt(preview, 3).get("parsedDescription").asText())
                .isEqualTo("Refund confusion");
        assertThat(rowAt(preview, 3).get("parsedAmount").isNull()).isTrue();

        // The stored row says the same thing as the response, which is what makes the response a
        // statement about the database rather than about the request.
        long batchId = batchIdOf(preview);
        assertThat(storedRowStatusOf(batchId, 3)).isEqualTo("ERROR");
        assertThat(storedBatchStatusOf(batchId)).isEqualTo("PREVIEWED");

        JsonNode committed = commitExpectingOk(token, batchId);

        // The commit rewrites the counters from its own walk: one row generated a transaction, one was
        // refused by the reader and never reached the procedure, and nothing was a duplicate.
        assertThat(committed.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(committed.get("modifiable").asBoolean()).isFalse();
        assertThat(committed.get("importedRows").asInt()).isEqualTo(2);
        assertThat(committed.get("validRows").asInt()).isEqualTo(2);
        assertThat(committed.get("errorRows").asInt()).isEqualTo(1);
        assertThat(committed.get("duplicateRows").asInt()).isZero();
        assertThat(committed.get("committedAt").isNull()).isFalse();

        assertThat(rowStatusAt(committed, 2)).isEqualTo("IMPORTED");
        assertThat(rowStatusAt(committed, 3)).isEqualTo("ERROR");
        assertThat(rowStatusAt(committed, 4)).isEqualTo("IMPORTED");
        assertThat(errorMessageAt(committed, 3)).isEqualTo(amountMessage);

        // The row's transactionId is the record it became, and the record is a CSV import filed under
        // the categories the file named - including the fallback for the row that named none.
        assertThat(importedTransactionIds(committed)).hasSize(2);
        long cafeTransaction = rowAt(committed, 2).get("transactionId").asLong();
        long textbookTransaction = rowAt(committed, 4).get("transactionId").asLong();

        assertThat(liveTransactionCountOf(userId)).isEqualTo(2);
        assertThat(columnInDatabase(cafeTransaction, "transactions", "source")).isEqualTo("CSV");
        assertThat(longValueFrom("SELECT category_id FROM transactions WHERE id = ?", cafeTransaction))
                .isEqualTo(defaultCategoryId(FOOD));
        // BR-05: the row named no category, so the procedure falls back to the default for the type.
        assertThat(longValueFrom("SELECT category_id FROM transactions WHERE id = ?", textbookTransaction))
                .isEqualTo(defaultCategoryId("Miscellaneous"));
        assertThat(columnInDatabase(textbookTransaction, "transactions", "txn_date"))
                .isEqualTo(LATER.toString());
    }

    @Test
    @DisplayName("UC-11: importing the same file twice imports nothing the second time")
    void importingTheSameFileTwiceImportsNothingTheSecondTime() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        String file = csv(rowOf(EARLIER, "18.00", "EXPENSE", "Campus Cafe", FOOD));

        JsonNode first = uploadExpectingCreated(token, file);
        assertThat(rowStatusAt(first, 2)).isEqualTo("VALID");
        assertThat(commitExpectingOk(token, batchIdOf(first)).get("importedRows").asInt()).isEqualTo(1);

        // The second upload is compared against the record the first one produced, so every row is
        // already recorded - which is the rule that stops a file imported twice from doubling every
        // figure the student has.
        JsonNode second = uploadExpectingCreated(token, file);
        assertThat(rowStatusAt(second, 2)).isEqualTo("DUPLICATE");
        assertThat(second.get("duplicateRows").asInt()).isEqualTo(1);
        assertThat(second.get("validRows").asInt()).isZero();
        assertThat(errorMessageAt(second, 2)).contains("You already have a record of 18.00");

        JsonNode committed = commitExpectingOk(token, batchIdOf(second));
        assertThat(committed.get("importedRows").asInt()).isZero();
        assertThat(liveTransactionCountOf(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-11: a purchase listed on two lines is reported on the second, not imported twice")
    void aFileListingTheSamePurchaseTwiceReportsTheSecondLine() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        JsonNode preview = uploadExpectingCreated(token, csv(
                rowOf(EARLIER, "60.00", "EXPENSE", "Hostel deposit", "Hostel/Rent"),
                rowOf(EARLIER, "60.00", "EXPENSE", "Hostel deposit", "Hostel/Rent")));

        assertThat(rowStatusAt(preview, 2)).isEqualTo("VALID");
        assertThat(rowStatusAt(preview, 3)).isEqualTo("DUPLICATE");
        // The note names the earlier date, which is how the student finds the record to look at.
        assertThat(errorMessageAt(preview, 3)).contains(EARLIER.toString());

        JsonNode committed = commitExpectingOk(token, batchIdOf(preview));
        assertThat(committed.get("importedRows").asInt()).isEqualTo(1);
        assertThat(liveTransactionCountOf(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-11: a row whose category cannot be resolved falls back to the type's default")
    void aRowWithNoResolvableCategoryFallsBackToTheDefault() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        // Neither name exists, so the preview resolves no category for either row and - because a
        // comparison needs something to compare - neither is a duplicate.
        JsonNode preview = uploadExpectingCreated(token, csv(
                rowOf(EARLIER, "22.00", "EXPENSE", "Something", "No Such Category"),
                rowOf(EARLIER, "33.00", "INCOME", "Something else", "No Such Category")));

        assertThat(rowStatusAt(preview, 2)).isEqualTo("VALID");
        assertThat(rowStatusAt(preview, 3)).isEqualTo("VALID");
        assertThat(rowAt(preview, 2).get("resolvedCategoryId").isNull()).isTrue();

        JsonNode committed = commitExpectingOk(token, batchIdOf(preview));

        List<Long> categories = List.of(
                longValueFrom("SELECT category_id FROM transactions WHERE id = ?",
                        rowAt(committed, 2).get("transactionId").asLong()),
                longValueFrom("SELECT category_id FROM transactions WHERE id = ?",
                        rowAt(committed, 3).get("transactionId").asLong()));

        assertThat(categories).containsExactly(
                defaultCategoryId("Miscellaneous"), defaultCategoryId("Other Income"));
        assertThat(liveTransactionCountOf(userId)).isEqualTo(2);
    }

    // ==================================================================
    //  Correcting a row
    // ==================================================================

    @Test
    @DisplayName("UC-11 B6: choosing a category re-decides the row's duplicate verdict, both ways")
    void choosingACategoryReDecidesTheDuplicateQuestion() throws Exception {
        String token = loginNewStudent();

        // The student already has this amount under Transport, and the file says Food - so the row is
        // importable. Correcting it to Transport makes it a duplicate they can now see.
        anExistingRecord(token, TRANSPORT, "75.00", EARLIER, "Bus pass");
        JsonNode preview = uploadExpectingCreated(token, csv(
                rowOf(LATER, "75.00", "EXPENSE", "Unclear merchant", FOOD)));
        long batchId = batchIdOf(preview);
        long rowId = rowIdAt(preview, 2);

        assertThat(rowStatusAt(preview, 2)).isEqualTo("VALID");

        JsonNode filed = fileRowExpectingOk(token, batchId, rowId, defaultCategoryId(TRANSPORT));
        assertThat(filed.get("rowStatus").asText()).isEqualTo("DUPLICATE");
        assertThat(filed.get("resolvedCategoryId").asLong()).isEqualTo(defaultCategoryId(TRANSPORT));
        assertThat(filed.get("errorMessage").asText()).contains("You already have a record of 75.00");

        // The verdict was written back, not merely reported: the batch's counters and its stored row
        // both moved, so a reload shows what the response showed.
        assertThat(storedRowStatusOf(batchId, 2)).isEqualTo("DUPLICATE");
        JsonNode reloaded = getBatchExpectingOk(token, batchId);
        assertThat(reloaded.get("duplicateRows").asInt()).isEqualTo(1);
        assertThat(reloaded.get("validRows").asInt()).isZero();

        // And back: correcting it to a category the student has no record under makes it importable
        // again, which is the direction that matters most - a false positive would otherwise lose a
        // record the student does have, with no way to say so.
        JsonNode refiled = fileRowExpectingOk(token, batchId, rowId, defaultCategoryId(FOOD));
        assertThat(refiled.get("rowStatus").asText()).isEqualTo("VALID");
        assertThat(refiled.get("errorMessage").isNull()).isTrue();
        assertThat(getBatchExpectingOk(token, batchId).get("duplicateRows").asInt()).isZero();
    }

    @Test
    @DisplayName("UC-11 B6: the chosen category is authoritative and the file's name is never re-derived")
    void theChosenCategoryIsNeverReDerivedFromTheFile() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        JsonNode preview = uploadExpectingCreated(token, csv(
                rowOf(EARLIER, "99.00", "EXPENSE", "Lunch", FOOD)));
        long batchId = batchIdOf(preview);
        Long transport = defaultCategoryId(TRANSPORT);

        fileRowExpectingOk(token, batchId, rowIdAt(preview, 2), transport);
        JsonNode committed = commitExpectingOk(token, batchId);

        assertThat(committed.get("importedRows").asInt()).isEqualTo(1);
        long transactionId = rowAt(committed, 2).get("transactionId").asLong();
        assertThat(longValueFrom("SELECT category_id FROM transactions WHERE id = ?", transactionId))
                .isEqualTo(transport);
        // BR-05: the record's type is its category's type, so correcting the category is also how a
        // wrong type in the file is corrected - the transaction carries no type of its own.
        assertThat(columnInDatabase(transactionId, "transactions", "import_batch_id"))
                .isEqualTo(String.valueOf(batchId));
        assertThat(liveTransactionCountOf(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-11 B6: a retired category, an unknown id and another student's category are refused")
    void theRowCategoryMustBeTheCallersOwnAndStillInUse() throws Exception {
        String token = loginNewStudent();
        String otherToken = loginNewStudent();

        JsonNode preview = uploadExpectingCreated(token, csv(
                rowOf(EARLIER, "40.00", "EXPENSE", "Lunch", FOOD)));
        long batchId = batchIdOf(preview);
        long rowId = rowIdAt(preview, 2);

        // A category of the caller's own, retired after the file was uploaded - so the row still reads
        // as importable and the endpoint has to be the one that refuses the choice.
        Long personal = createPersonalCategory(token, "Coffee Runs");
        send(HttpMethod.PATCH, "/api/v1/categories/" + personal, token, Map.of("isActive", false));

        ResponseEntity<String> retired = fileRow(token, batchId, rowId, personal);
        assertThat(retired.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCodeOf(retired)).isEqualTo("VALIDATION_ERROR");
        assertThat(fieldNamesIn(body(retired))).containsExactly("categoryId");

        // An id that names nothing, and one that names another student's category, are answered
        // identically - telling them apart would disclose whose category an id is.
        assertThat(fileRow(token, batchId, rowId, 999_999_999L).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        Long foreign = createPersonalCategory(otherToken, "Their Own Category");
        assertThat(fileRow(token, batchId, rowId, foreign).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // And nothing moved: the row is still importable and unfiled.
        assertThat(rowStatusAt(getBatchExpectingOk(token, batchId), 2)).isEqualTo("VALID");
        assertThat(rowAt(getBatchExpectingOk(token, batchId), 2).get("resolvedCategoryId").isNull())
                .isTrue();
    }

    // ==================================================================
    //  What the commit's own rules do
    // ==================================================================

    @Test
    @DisplayName("UC-11: one row the database refuses is reported, and the rest of the file imports")
    void oneRowTheDatabaseRefusesDoesNotEndTheImport() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        // The reader does not check the date against the clock - BR-08 is the database's rule and the
        // procedure enforces it per row - so this file previews as two importable rows and only the
        // commit can tell the student that one of them cannot be recorded.
        JsonNode preview = uploadExpectingCreated(token, csv(
                rowOf(IN_THE_FUTURE, "50.00", "EXPENSE", "Tomorrow's lunch", FOOD),
                rowOf(EARLIER, "50.00", "EXPENSE", "Today's lunch", FOOD)));

        assertThat(rowStatusAt(preview, 2)).isEqualTo("VALID");
        assertThat(rowStatusAt(preview, 3)).isEqualTo("VALID");

        JsonNode committed = commitExpectingOk(token, batchIdOf(preview));

        // The refused row becomes an ERROR row with a sentence, and the walk continues - which is the
        // procedure's per-row handler rather than a Java-side all-or-nothing transaction.
        assertThat(rowStatusAt(committed, 2)).isEqualTo("ERROR");
        assertThat(errorMessageAt(committed, 2))
                .isEqualTo("Rejected by validation (BR-02/BR-07/BR-08)");
        assertThat(rowStatusAt(committed, 3)).isEqualTo("IMPORTED");
        assertThat(committed.get("importedRows").asInt()).isEqualTo(1);
        assertThat(committed.get("errorRows").asInt()).isEqualTo(1);
        assertThat(liveTransactionCountOf(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-11: a category retired between the preview and the commit turns the row into an error")
    void aCategoryRetiredSinceThePreviewTurnsTheRowIntoAnError() throws Exception {
        String token = loginNewStudent();

        JsonNode preview = uploadExpectingCreated(token, csv(
                rowOf(EARLIER, "64.00", "EXPENSE", "Lunch", FOOD)));
        long batchId = batchIdOf(preview);

        Long personal = createPersonalCategory(token, "Lunch Money");
        fileRowExpectingOk(token, batchId, rowIdAt(preview, 2), personal);

        // Retired after the student chose it, so the choice is still stored but no longer usable. The
        // procedure must not silently resolve the file's own name instead - the student explicitly did
        // not pick it - so the row becomes an error they can see.
        send(HttpMethod.PATCH, "/api/v1/categories/" + personal, token, Map.of("isActive", false));

        JsonNode committed = commitExpectingOk(token, batchId);
        assertThat(rowStatusAt(committed, 2)).isEqualTo("ERROR");
        assertThat(errorMessageAt(committed, 2))
                .isEqualTo("The category chosen for this row is no longer available");
        assertThat(committed.get("importedRows").asInt()).isZero();
        assertThat(liveTransactionCountOf(userIdOf(token))).isZero();
    }

    // ==================================================================
    //  What the commit teaches
    // ==================================================================

    @Test
    @DisplayName("UC-08 during UC-11: the commit teaches each imported row's description")
    void theCommitTeachesWhatTheStudentsFileEstablished() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        JsonNode preview = uploadExpectingCreated(token, csv(
                rowOf(EARLIER, "12.50", "EXPENSE", "Campus Cafe Latte", FOOD),
                rowOf(EARLIER, "8.00", "EXPENSE", "", FOOD),
                rowOf(EARLIER, "bad", "EXPENSE", "Never imported", FOOD)));

        assertThat(rowStatusAt(preview, 4)).isEqualTo("ERROR");

        commitExpectingOk(token, batchIdOf(preview));

        // One mapping, from the one row that was imported and had a description worth learning from.
        assertThat(storedRuleCategoryNameOf(userId, "campus cafe latte")).isEqualTo(FOOD);
        // RuleSource.IMPORT, which is the member this module is the writer of.
        assertThat(storedRuleSourceOf(userId, "campus cafe latte")).isEqualTo("IMPORT");
        // A row with no description and a row that was never imported both teach nothing, and that is
        // an ordinary outcome rather than a failure: the rows were imported, they just changed nothing.
        assertThat(countOf("SELECT COUNT(*) FROM category_rules WHERE user_id = ?", userId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("UC-08 during UC-11: a learned mapping suggests a category, which is advice and not a filing")
    void aLearnedMappingSuggestsACategoryWithoutFilingTheRow() throws Exception {
        String token = loginNewStudent();

        // First import: the student's own file teaches the merchant, in the same step that imports it.
        JsonNode teaching = uploadExpectingCreated(token, csv(
                rowOf(EARLIER, "12.50", "EXPENSE", "Campus Cafe Latte", FOOD)));
        commitExpectingOk(token, batchIdOf(teaching));

        // Second import: the same merchant, but the file files it under Transport. The preview offers
        // the learned category as advice; the row is still whatever the file said.
        JsonNode preview = uploadExpectingCreated(token, csv(
                rowOf(LATER, "14.00", "EXPENSE", "Campus Cafe Latte", TRANSPORT)));
        JsonNode row = rowAt(preview, 2);

        assertThat(row.get("aiSuggestedCategoryId").asLong()).isEqualTo(defaultCategoryId(FOOD));
        // OB-006: the suggestion is drawn from the caller's own visible categories, so it can only ever
        // name a category they may file under. There is no other code path to the column.
        // And it is advice: the row's own category is untouched and unfiled, and the loaded id is not
        // written where the procedure would treat it as the student's own choice.
        assertThat(row.get("parsedCategoryName").asText()).isEqualTo(TRANSPORT);
        assertThat(row.get("resolvedCategoryId").isNull()).isTrue();

        // BR-13: the commit files it under the file's own name, not under what the system suggested.
        JsonNode committed = commitExpectingOk(token, batchIdOf(preview));
        assertThat(longValueFrom("SELECT category_id FROM transactions WHERE id = ?",
                rowAt(committed, 2).get("transactionId").asLong()))
                .isEqualTo(defaultCategoryId(TRANSPORT));
    }

    // ==================================================================
    //  Reading the preview back
    // ==================================================================

    @Test
    @DisplayName("UC-11 A1: the file's own line is returned as an object, quoting and all")
    void theStoredLineIsReturnedAsAnObject() throws Exception {
        String token = loginNewStudent();

        // A description containing the delimiter and a quote: the two things a naive split would break,
        // and the reason the response carries the line as an object rather than as a string of JSON.
        JsonNode preview = uploadExpectingCreated(token,
                csv("2026-09-18,12.50,EXPENSE,\"Campus Cafe, \"\"corner\"\" branch\",Food"));
        JsonNode row = rowAt(preview, 2);

        assertThat(row.get("parsedDescription").asText())
                .isEqualTo("Campus Cafe, \"corner\" branch");
        assertThat(row.get("rawData").isObject()).isTrue();
        assertThat(row.get("rawData").get("description").asText())
                .isEqualTo("Campus Cafe, \"corner\" branch");
        assertThat(row.get("rawData").get("amount").asText()).isEqualTo("12.50");
        assertThat(row.get("parsedAmount").asDouble()).isEqualTo(12.50);
    }

    @Test
    @DisplayName("UC-11: the list returns the caller's own imports, newest first, and nobody else's")
    void theListReturnsOnlyTheCallersImportsNewestFirst() throws Exception {
        String token = loginNewStudent();
        String otherToken = loginNewStudent();

        // A student who has never imported anything has an empty history, which is a fact about their
        // own data rather than a missing resource - so it is a 200 with no entries, not a 404.
        ResponseEntity<String> empty = send(HttpMethod.GET, IMPORTS_URL, token, null);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(empty).get("limit").asInt()).isEqualTo(20);
        assertThat(body(empty).get("entries")).isEmpty();

        JsonNode first = uploadExpectingCreated(token, csv(rowOf(EARLIER, "10.00", "EXPENSE", "A", FOOD)));
        JsonNode second = uploadExpectingCreated(token, csv(rowOf(EARLIER, "20.00", "EXPENSE", "B", FOOD)));

        // The other student's upload exists in the same database and must not appear.
        uploadExpectingCreated(otherToken, csv(rowOf(EARLIER, "30.00", "EXPENSE", "C", FOOD)));

        JsonNode list = list(token);
        assertThat(list.get("entries")).hasSize(2);
        assertThat(list.get("entries").get(0).get("id").asLong()).isEqualTo(batchIdOf(second));
        assertThat(list.get("entries").get(1).get("id").asLong()).isEqualTo(batchIdOf(first));
        assertThat(list.get("entries").get(0).get("originalFilename").asText()).isEqualTo("records.csv");
        assertThat(list.get("entries").get(0).get("status").asText()).isEqualTo("PREVIEWED");
        assertThat(list.get("entries").get(0).get("modifiable").asBoolean()).isTrue();

        // A committed batch is no longer modifiable, which is how a student finds the one they were
        // part way through rather than one that is finished.
        commitExpectingOk(token, batchIdOf(second));
        JsonNode after = list(token);
        assertThat(after.get("entries").get(0).get("status").asText()).isEqualTo("COMMITTED");
        assertThat(after.get("entries").get(0).get("modifiable").asBoolean()).isFalse();
        assertThat(after.get("entries").get(0).get("importedRows").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-11: the list is bounded, and an out-of-range limit is refused rather than clamped")
    void theLimitIsRefusedRatherThanClamped() throws Exception {
        // Refused rather than reduced, because the response reports the limit it applied and a silently
        // reduced answer would make that field untrue.
        String token = loginNewStudent();
        uploadExpectingCreated(token, csv(rowOf(EARLIER, "10.00", "EXPENSE", "A", FOOD)));
        uploadExpectingCreated(token, csv(rowOf(EARLIER, "20.00", "EXPENSE", "B", FOOD)));

        ResponseEntity<String> limited = listWithLimit(token, "1");
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(limited).get("limit").asInt()).isEqualTo(1);
        assertThat(body(limited).get("entries")).hasSize(1);

        assertThat(listWithLimit(token, "100").getStatusCode()).isEqualTo(HttpStatus.OK);

        for (String refused : List.of("0", "-1", "101")) {
            ResponseEntity<String> response = listWithLimit(token, refused);
            assertThat(response.getStatusCode())
                    .as("limit=%s body=%s", refused, response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        // A value that is not a number at all is answered as a bad parameter rather than as a field
        // error, because there is no field to point at.
        assertThat(listWithLimit(token, "abc").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(fieldNamesIn(body(listWithLimit(token, "101")))).containsExactly("limit");
    }

    // ==================================================================
    //  Abandoning an import
    // ==================================================================

    @Test
    @DisplayName("UC-11 A2: cancelling keeps the rows, imports nothing, and cannot be repeated")
    void cancellingKeepsTheRowsAndImportsNothing() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        JsonNode preview = uploadExpectingCreated(token, csv(
                rowOf(EARLIER, "12.50", "EXPENSE", "Campus Cafe", FOOD)));
        long batchId = batchIdOf(preview);

        JsonNode cancelled = cancelExpectingOk(token, batchId);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("modifiable").asBoolean()).isFalse();
        // The counters are the preview's, because the procedure never ran and never rewrote them.
        assertThat(cancelled.get("totalRows").asInt()).isEqualTo(1);
        assertThat(cancelled.get("importedRows").asInt()).isZero();

        // The rows survive the decision not to import them, so the student can still see what they had.
        assertThat(storedRowCountOf(batchId)).isEqualTo(1);
        assertThat(cancelled.get("rows")).hasSize(1);
        assertThat(rowStatusAt(cancelled, 2)).isEqualTo("VALID");
        assertThat(liveTransactionCountOf(userId)).isZero();

        // Cancelling again is refused, and so is committing what was abandoned.
        assertThat(cancel(token, batchId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(commit(token, batchId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(countOf("SELECT COUNT(*) FROM import_rows WHERE batch_id = ? AND row_status = ?",
                batchId, "IMPORTED")).isZero();
    }

    @Test
    @DisplayName("UC-11: a settled import refuses a further commit, cancel and row change")
    void aSettledImportCanNoLongerBeChanged() throws Exception {
        String token = loginNewStudent();

        JsonNode preview = uploadExpectingCreated(token, csv(
                rowOf(EARLIER, "12.50", "EXPENSE", "Campus Cafe", FOOD)));
        long batchId = batchIdOf(preview);
        long rowId = rowIdAt(preview, 2);

        commitExpectingOk(token, batchId);

        assertThat(commit(token, batchId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCodeOf(commit(token, batchId))).isEqualTo("DATA_CONFLICT");
        assertThat(cancel(token, batchId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(fileRow(token, batchId, rowId, defaultCategoryId(TRANSPORT)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ==================================================================
    //  Files that cannot be previewed
    // ==================================================================

    @Test
    @DisplayName("UC-11 A1: a file that cannot be read as CSV is refused, and no batch is stored")
    void anUnusableFileIsRefusedAndStoresNothing() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        // Every one of these is a thing about the file the student chose, so every one is a field error
        // on `content` - and none of them leaves a half-built batch behind, which is what makes "the
        // file either becomes a preview or nothing was stored" true.
        List<String> refused = List.of(
                "",
                "   \n  ",
                "just some text with no commas at all",
                // A header with no rows: there is nothing to import, so there is nothing to preview.
                "date,amount,type,description,category\n",
                // Required columns missing, named in the message so the student knows what to add.
                "amount,type,description\n12.50,EXPENSE,Lunch\n",
                "date,type,description\n2026-09-18,EXPENSE,Lunch\n",
                // A quoted value that is never closed. Reading on to the end of the file would swallow
                // every remaining row into one field, which is why this refuses instead of guessing.
                "date,amount,type,description,category\n"
                        + "2026-09-18,12.50,EXPENSE,\"unclosed,Food\n");

        for (String content : refused) {
            ResponseEntity<String> response = upload(token, "records.csv", content);
            assertThat(response.getStatusCode())
                    .as("content=%s body=%s", content, response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_ERROR");
            assertThat(fieldNamesIn(body(response))).containsExactly("content");
        }

        assertThat(storedBatchCountOf(userId)).isZero();

        // The message names the column that is missing, which is a thing the student can go and fix.
        ResponseEntity<String> missingType = upload(token, "records.csv",
                "date,amount,description\n2026-09-18,12.50,Lunch\n");
        assertThat(body(missingType).get("message").asText()).contains("type");

        // A filename the column cannot hold is a body field error, caught before anything is read.
        ResponseEntity<String> longName = upload(token, "n".repeat(300),
                csv(rowOf(EARLIER, "10.00", "EXPENSE", "A", FOOD)));
        assertThat(longName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldNamesIn(body(longName))).containsExactly("filename");

        assertThat(storedBatchCountOf(userId)).isZero();
    }

    @Test
    @DisplayName("UC-11 A1: a file above the row or size cap is refused rather than truncated")
    void anOverlargeFileIsRefusedRatherThanTruncated() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);

        // A preview of part of a file would be showing the student something other than their file, so
        // the cap is a refusal and not a cut.
        ResponseEntity<String> tooManyRows =
                upload(token, "big.csv", csvWithNumberOfRows(2001));
        assertThat(tooManyRows.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(tooManyRows).get("message").asText()).contains("2000");

        // The row cap does not bound the request on its own - one row may carry a very long description
        // - so a second bound covers it, and it is refused for the same reason.
        String padding = "d".repeat(2_000_001);
        ResponseEntity<String> tooLong = upload(token, "long.csv", csv(
                rowOf(EARLIER, "10.00", "EXPENSE", padding, FOOD)));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldNamesIn(body(tooLong))).containsExactly("content");

        assertThat(storedBatchCountOf(userId)).isZero();

        // The cap is a limit and not a wall: a file of exactly the documented row count imports.
        JsonNode atTheCap = uploadExpectingCreated(token, csvWithNumberOfRows(2000));
        assertThat(atTheCap.get("totalRows").asInt()).isEqualTo(2000);
        assertThat(atTheCap.get("errorRows").asInt()).isZero();
        // Every row is identical, so every row after the first is a duplicate of one this file already
        // carries - which is the detector chaining within a single file rather than against the table.
        assertThat(atTheCap.get("duplicateRows").asInt()).isEqualTo(1999);
    }

    @Test
    @DisplayName("UC-11: a batch that is not the caller's is answered as one that does not exist")
    void anotherStudentsBatchIsNotFound() throws Exception {
        String ownerToken = loginNewStudent();
        String strangerToken = loginNewStudent();

        JsonNode preview = uploadExpectingCreated(ownerToken, csv(
                rowOf(EARLIER, "12.50", "EXPENSE", "Campus Cafe", FOOD)));
        long batchId = batchIdOf(preview);
        long rowId = rowIdAt(preview, 2);

        // Section 7.5: telling the two apart would let a client enumerate other students' batch ids.
        for (ResponseEntity<String> response : List.of(
                getBatch(strangerToken, batchId),
                commit(strangerToken, batchId),
                cancel(strangerToken, batchId))) {
            assertThat(response.getStatusCode())
                    .as("body=%s", response.getBody())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(errorCodeOf(response)).isEqualTo("NOT_FOUND");
        }

        assertThat(fileRow(strangerToken, batchId, rowId, defaultCategoryId(FOOD)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // A row id that is the caller's own but in the wrong batch is the same missing-row answer.
        String secondOwner = loginNewStudent();
        JsonNode other = uploadExpectingCreated(secondOwner, csv(
                rowOf(EARLIER, "20.00", "EXPENSE", "Lunch", FOOD)));
        assertThat(fileRow(secondOwner, batchIdOf(other), rowId, defaultCategoryId(FOOD))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Nothing the stranger did moved the owner's batch.
        assertThat(storedBatchStatusOf(batchId)).isEqualTo("PREVIEWED");
        assertThat(storedRowStatusOf(batchId, 2)).isEqualTo("VALID");
    }

    // ==================================================================
    //  Security (section 7.5)
    // ==================================================================

    @Test
    @DisplayName("Section 7.5: no token is 401, and an administrator's token is 403 on every route")
    void roleAndTokenAreEnforced() throws Exception {
        String token = loginNewStudent();
        String adminToken = adminLogin();
        JsonNode preview = uploadExpectingCreated(token, csv(
                rowOf(EARLIER, "12.50", "EXPENSE", "Campus Cafe", FOOD)));
        long batchId = batchIdOf(preview);
        long rowId = rowIdAt(preview, 2);
        Map<String, Object> fileBody = Map.of("categoryId", defaultCategoryId(FOOD));
        Map<String, Object> uploadBody = Map.of("filename", "records.csv",
                "content", csv(rowOf(EARLIER, "10.00", "EXPENSE", "A", FOOD)));

        // The route matches none of the student prefixes above it except its own, so without that rule
        // it would fall to /api/** - which admits any authenticated account. This is the assertion that
        // fails if the rule is ever dropped, and it is why `/api/v1/imports/**` has one.
        assertThat(send(HttpMethod.POST, IMPORTS_URL, null, uploadBody).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.GET, IMPORTS_URL, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.GET, IMPORTS_URL + "/" + batchId, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.PATCH, IMPORTS_URL + "/" + batchId + "/rows/" + rowId, null,
                fileBody).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.POST, IMPORTS_URL + "/" + batchId + "/commit", null, null)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.POST, IMPORTS_URL + "/" + batchId + "/cancel", null, null)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // An administrator has no use case for the route, and it writes: an upload stores a batch and
        // its rows, a commit generates transactions and teaches keyword mappings. A role admitted by
        // accident could put records on a student's account from a screen with no reason to.
        assertThat(errorCodeOf(send(HttpMethod.GET, IMPORTS_URL, adminToken, null)))
                .isEqualTo("ACCESS_DENIED");
        assertThat(send(HttpMethod.GET, IMPORTS_URL, adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, IMPORTS_URL, adminToken, uploadBody).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, IMPORTS_URL + "/" + batchId + "/commit", adminToken, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.PATCH, IMPORTS_URL + "/" + batchId + "/rows/" + rowId, adminToken,
                fileBody).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(storedBatchStatusOf(batchId)).isEqualTo("PREVIEWED");
    }

    // ==================================================================
    //  Helpers
    // ==================================================================

    /** Creates a category of the caller's own (UC-06) and returns its id. */
    private Long createPersonalCategory(String token, String name) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/categories", token,
                Map.of("name", name, "type", "EXPENSE"));
        assertThat(response.getStatusCode())
                .as("create category body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return body(response).get("id").asLong();
    }
}
