package com.campuscoin.imports.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.imports.entity.ImportRowDraft;
import com.campuscoin.imports.entity.ImportRowStatus;

class ImportRowReaderTest {

    private final ImportRowReader reader = new ImportRowReader();

    @Test
    @DisplayName("UC-11: a well-formed row is read into its typed values")
    void aWellFormedRowIsRead() {
        ImportRowDraft draft = read(row(Map.of(
                "date", "2026-09-01",
                "amount", "12.50",
                "type", "EXPENSE",
                "description", "Campus cafe",
                "category", "Food")));

        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.VALID);
        assertThat(draft.parsedDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(draft.parsedAmount()).isEqualByComparingTo("12.50");
        assertThat(draft.parsedType()).isEqualTo(CategoryType.EXPENSE);
        assertThat(draft.parsedDescription()).isEqualTo("Campus cafe");
        assertThat(draft.parsedCategoryName()).isEqualTo("Food");
        assertThat(draft.errorMessage()).isNull();
        assertThat(draft.aiSuggestedCategoryId()).isNull();
    }

    @Test
    @DisplayName("UC-11: the row keeps the file's line number, not the data's")
    void theLineNumberIsCarriedThrough() {

        ImportRowDraft draft = read(new CsvParser.CsvRecord(7, Map.of(
                "date", "2026-09-01", "amount", "1.00", "type", "EXPENSE")));

        assertThat(draft.csvRowNo()).isEqualTo(7);
    }

    @Test
    @DisplayName("UC-11: a row with no description and no category is importable")
    void descriptionAndCategoryAreOptional() {

        ImportRowDraft draft = read(row(Map.of(
                "date", "2026-09-01", "amount", "1.00", "type", "EXPENSE")));

        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.VALID);
        assertThat(draft.parsedDescription()).isNull();
        assertThat(draft.parsedCategoryName()).isNull();
    }

    @Test
    @DisplayName("UC-11: surrounding space on a value is trimmed")
    void valuesAreTrimmed() {
        ImportRowDraft draft = read(row(Map.of(
                "date", " 2026-09-01 ",
                "amount", " 12.50 ",
                "type", " expense ",
                "description", "  Campus cafe  ")));

        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.VALID);
        assertThat(draft.parsedType()).isEqualTo(CategoryType.EXPENSE);
        assertThat(draft.parsedDescription()).isEqualTo("Campus cafe");
    }

    @Test
    @DisplayName("UC-11: both the ISO and the day-first date forms are accepted")
    void bothDateFormsAreAccepted() {

        assertThat(read(row(Map.of("date", "2026-09-01", "amount", "1.00", "type", "EXPENSE")))
                .parsedDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(read(row(Map.of("date", "1/9/2026", "amount", "1.00", "type", "EXPENSE")))
                .parsedDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    @DisplayName("UC-11: a day that does not exist is refused rather than rolled forward")
    void anImpossibleDateIsRefusedRatherThanRolled() {

        ImportRowDraft draft = read(row(Map.of(
                "date", "2026-02-30", "amount", "1.00", "type", "EXPENSE")));

        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.ERROR);
        assertThat(draft.parsedDate()).isNull();
        assertThat(draft.errorMessage()).isEqualTo("Date must be a date, as YYYY-MM-DD or D/M/YYYY.");
    }

    @Test
    @DisplayName("UC-11: a missing date is refused with its own message, not the format's")
    void aMissingDateIsItsOwnMessage() {

        assertThat(read(row(Map.of("amount", "1.00", "type", "EXPENSE"))).errorMessage())
                .isEqualTo("Date is missing.");
    }

    @Test
    @DisplayName("UC-11: a date in the future is readable here, because the clock is not this class's rule")
    void aFutureDateIsReadableHere() {

        ImportRowDraft draft = read(row(Map.of(
                "date", "2099-01-01", "amount", "1.00", "type", "EXPENSE")));

        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.VALID);
        assertThat(draft.parsedDate()).isEqualTo(LocalDate.of(2099, 1, 1));
    }

    @Test
    @DisplayName("UC-11: a thousands separator is stripped, because a spreadsheet writes one")
    void aThousandsSeparatorIsAccepted() {
        ImportRowDraft draft = read(row(Map.of(
                "date", "2026-09-01", "amount", "1,234.50", "type", "EXPENSE")));

        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.VALID);
        assertThat(draft.parsedAmount()).isEqualByComparingTo("1234.50");
    }

    @Test
    @DisplayName("UC-11: an amount is stored with two decimal places")
    void anAmountIsStoredAtTheColumnsScale() {

        ImportRowDraft draft = read(row(Map.of(
                "date", "2026-09-01", "amount", "12.5", "type", "EXPENSE")));

        assertThat(draft.parsedAmount()).isEqualByComparingTo("12.50");
        assertThat(draft.parsedAmount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("UC-11: a zero, a negative figure and a third decimal place are all refused")
    void amountsOutsideTheColumnsRulesAreRefused() {

        String message = "Amount must be a positive number, with at most 2 decimal places.";

        for (String amount : new String[]{"0", "0.00", "-1.00", "1.234"}) {
            ImportRowDraft draft = read(row(Map.of(
                    "date", "2026-09-01", "amount", amount, "type", "EXPENSE")));

            assertThat(draft.rowStatus()).as("amount=%s", amount).isEqualTo(ImportRowStatus.ERROR);
            assertThat(draft.errorMessage()).as("amount=%s", amount).isEqualTo(message);
            assertThat(draft.parsedAmount()).as("amount=%s", amount).isNull();
        }
    }

    @Test
    @DisplayName("UC-11: an amount wider than DECIMAL(15,2) is refused")
    void anAmountTooWideForTheColumnIsRefused() {

        ImportRowDraft tooWide = read(row(Map.of(
                "date", "2026-09-01", "amount", "12345678901234.00", "type", "EXPENSE")));
        ImportRowDraft atTheBound = read(row(Map.of(
                "date", "2026-09-01", "amount", "1234567890123.00", "type", "EXPENSE")));

        assertThat(tooWide.rowStatus()).isEqualTo(ImportRowStatus.ERROR);
        assertThat(atTheBound.rowStatus()).isEqualTo(ImportRowStatus.VALID);
    }

    @Test
    @DisplayName("UC-11: a value that is not a number at all is refused like any other bad amount")
    void aNonNumericAmountIsRefused() {
        ImportRowDraft draft = read(row(Map.of(
                "date", "2026-09-01", "amount", "twelve", "type", "EXPENSE")));

        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.ERROR);
        assertThat(draft.errorMessage())
                .isEqualTo("Amount must be a positive number, with at most 2 decimal places.");
    }

    @Test
    @DisplayName("UC-11: a missing amount is refused with its own message")
    void aMissingAmountIsItsOwnMessage() {
        assertThat(read(row(Map.of("date", "2026-09-01", "type", "EXPENSE"))).errorMessage())
                .isEqualTo("Amount is missing.");
    }

    @Test
    @DisplayName("UC-11: the type is matched whatever its case")
    void theTypeIsMatchedCaseInsensitively() {

        for (String type : new String[]{"EXPENSE", "expense", "Expense", " expense "}) {
            assertThat(read(row(Map.of("date", "2026-09-01", "amount", "1.00", "type", type)))
                    .parsedType()).as("type=%s", type).isEqualTo(CategoryType.EXPENSE);
        }
    }

    @Test
    @DisplayName("UC-11: a type that is neither member is refused, and a missing one says so")
    void anUnknownTypeIsRefused() {
        ImportRowDraft unknown = read(row(Map.of(
                "date", "2026-09-01", "amount", "1.00", "type", "TRANSFER")));
        ImportRowDraft missing = read(row(Map.of("date", "2026-09-01", "amount", "1.00")));

        assertThat(unknown.parsedType()).isNull();
        assertThat(unknown.errorMessage()).isEqualTo("Type must be INCOME or EXPENSE.");
        assertThat(missing.errorMessage()).isEqualTo("Type is missing.");
    }

    @Test
    @DisplayName("UC-11: a row with several problems reports all of them at once")
    void everyProblemOnARowIsReportedTogether() {

        ImportRowDraft draft = read(row(Map.of("description", "nothing useful")));

        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.ERROR);
        assertThat(draft.errorMessage())
                .isEqualTo("Date is missing. Amount is missing. Type is missing.");
    }

    @Test
    @DisplayName("UC-11: a refused row keeps every value that did parse")
    void aRefusedRowKeepsWhatItCouldRead() {

        ImportRowDraft draft = read(row(Map.of(
                "date", "2026-09-01",
                "amount", "oops",
                "type", "EXPENSE",
                "description", "Refund confusion")));

        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.ERROR);
        assertThat(draft.parsedDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(draft.parsedType()).isEqualTo(CategoryType.EXPENSE);
        assertThat(draft.parsedDescription()).isEqualTo("Refund confusion");
        assertThat(draft.parsedAmount()).isNull();
    }

    @Test
    @DisplayName("UC-11: a description longer than its column is cut, not refused")
    void aLongDescriptionIsCutRatherThanRefused() {

        String longDescription = "x".repeat(ImportRowReader.MAX_DESCRIPTION_LENGTH + 40);

        ImportRowDraft draft = read(row(Map.of(
                "date", "2026-09-01", "amount", "1.00", "type", "EXPENSE",
                "description", longDescription)));

        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.VALID);
        assertThat(draft.parsedDescription()).hasSize(ImportRowReader.MAX_DESCRIPTION_LENGTH);
        assertThat(draft.parsedDescription()).isEqualTo(
                longDescription.substring(0, ImportRowReader.MAX_DESCRIPTION_LENGTH));
    }

    @Test
    @DisplayName("UC-11: a category name longer than its column is cut too")
    void aLongCategoryNameIsCut() {
        ImportRowDraft draft = read(row(Map.of(
                "date", "2026-09-01", "amount", "1.00", "type", "EXPENSE",
                "category", "c".repeat(ImportRowReader.MAX_CATEGORY_NAME_LENGTH + 10))));

        assertThat(draft.parsedCategoryName()).hasSize(ImportRowReader.MAX_CATEGORY_NAME_LENGTH);
    }

    @Test
    @DisplayName("UC-11: a value that fits is returned unchanged")
    void aShortValueIsUnchanged() {

        String exact = "y".repeat(ImportRowReader.MAX_DESCRIPTION_LENGTH);

        assertThat(read(row(Map.of("date", "2026-09-01", "amount", "1.00", "type", "EXPENSE",
                "description", exact))).parsedDescription()).isEqualTo(exact);
    }

    @Test
    @DisplayName("UC-11: the row is kept as a JSON object keyed by column")
    void theRawLineIsKeptAsJson() {

        ImportRowDraft draft = read(row(Map.of(
                "date", "2026-09-01", "amount", "12.50", "type", "EXPENSE",
                "description", "Campus cafe")));

        assertThat(draft.rawData())
                .startsWith("{")
                .contains("\"date\":\"2026-09-01\"")
                .contains("\"amount\":\"12.50\"")
                .contains("\"description\":\"Campus cafe\"");
    }

    @Test
    @DisplayName("UC-11: a quote or a backslash in a value does not corrupt the stored JSON")
    void specialCharactersAreEscapedInTheStoredLine() {

        ImportRowDraft draft = read(row(Map.of(
                "date", "2026-09-01", "amount", "1.00", "type", "EXPENSE",
                "description", "said \"hello\" at C:\\temp")));

        assertThat(draft.rawData())
                .contains("\\\"hello\\\"")
                .contains("C:\\\\temp");
    }

    @Test
    @DisplayName("UC-11: a row with no error carries no message, and a refused one always does")
    void theMessageIsPresentExactlyOnARefusedRow() {

        ImportRowDraft valid = read(row(Map.of(
                "date", "2026-09-01", "amount", "1.00", "type", "EXPENSE")));
        ImportRowDraft refused = read(row(Map.of("date", "2026-09-01", "amount", "x", "type", "EXPENSE")));

        assertThat(valid.errorMessage()).isNull();
        assertThat(refused.errorMessage()).isNotNull();
    }

    private ImportRowDraft read(CsvParser.CsvRecord record) {
        return reader.read(record);
    }

    private static CsvParser.CsvRecord row(Map<String, String> values) {
        return new CsvParser.CsvRecord(2, values);
    }
}
