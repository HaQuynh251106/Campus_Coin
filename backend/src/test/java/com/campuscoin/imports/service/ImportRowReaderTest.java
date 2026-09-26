package com.campuscoin.imports.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.imports.entity.ImportRowDraft;
import com.campuscoin.imports.entity.ImportRowStatus;

/**
 * One CSV row's values, tested directly rather than through an upload.
 *
 * <p><b>Why this is a unit test.</b> {@link ImportRowReader} takes a parsed record and returns a draft:
 * no repository, no transaction, no clock. Every case is therefore a handful of strings, and the cases
 * worth pinning are boundaries a seeded file reaches awkwardly - a day that does not exist in the month
 * it names, an amount one digit too wide for {@code DECIMAL(15,2)}, a description one character over
 * the column's width.
 *
 * <p><b>The two rules this class does <em>not</em> apply are asserted as absences.</b> It does not
 * decide whether a category exists or is the student's own, and it does not look at the clock: both are
 * the commit's (BR-02/BR-05/BR-07, and BR-08 in {@code sp_apply_csv_batch}). A row dated in the future
 * is therefore readable here and refused there, which is exactly why the preview shows it as importable
 * and the commit turns it into an error - a sequence the integration suite pins end to end.
 *
 * <p><b>Every refusal keeps what it could read.</b> A row whose amount is a typo is still shown with its
 * date and its description beside the reason, rather than as a row of blanks - so the assertions below
 * test both halves: the message, and the values that survived beside it.
 */
class ImportRowReaderTest {

    private final ImportRowReader reader = new ImportRowReader();

    // ------------------------------------------------------------------
    //  A good row
    // ------------------------------------------------------------------

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
        // The draft's csvRowNo is written to import_rows.csv_row_no and is what every message is filed
        // under, so the offset the parser established has to survive this step unchanged.
        ImportRowDraft draft = read(new CsvParser.CsvRecord(7, Map.of(
                "date", "2026-09-01", "amount", "1.00", "type", "EXPENSE")));

        assertThat(draft.csvRowNo()).isEqualTo(7);
    }

    @Test
    @DisplayName("UC-11: a row with no description and no category is importable")
    void descriptionAndCategoryAreOptional() {
        // BR-13's other half: the file need not name a category, because the commit falls back to the
        // default for the type. An absent description is ordinary rather than a problem.
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

    // ------------------------------------------------------------------
    //  Dates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: both the ISO and the day-first date forms are accepted")
    void bothDateFormsAreAccepted() {
        // ISO is what this application's own export produces, so a file exported here and imported back
        // works. D/M/YYYY is what a Vietnamese or British spreadsheet writes.
        assertThat(read(row(Map.of("date", "2026-09-01", "amount", "1.00", "type", "EXPENSE")))
                .parsedDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(read(row(Map.of("date", "1/9/2026", "amount", "1.00", "type", "EXPENSE")))
                .parsedDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    @DisplayName("UC-11: a day that does not exist is refused rather than rolled forward")
    void anImpossibleDateIsRefusedRatherThanRolled() {
        // The reason the formats use STRICT with uuuu rather than yyyy. Under SMART, 2026-02-30 resolves
        // to 2026-03-02 and the file imports the wrong date silently - a typo turned into a record the
        // student never made.
        ImportRowDraft draft = read(row(Map.of(
                "date", "2026-02-30", "amount", "1.00", "type", "EXPENSE")));

        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.ERROR);
        assertThat(draft.parsedDate()).isNull();
        assertThat(draft.errorMessage()).isEqualTo("Date must be a date, as YYYY-MM-DD or D/M/YYYY.");
    }

    @Test
    @DisplayName("UC-11: a missing date is refused with its own message, not the format's")
    void aMissingDateIsItsOwnMessage() {
        // "Date is missing" and "Date must be a date" are different remedies for the student: one is a
        // column that is empty, the other is a value that is wrong.
        assertThat(read(row(Map.of("amount", "1.00", "type", "EXPENSE"))).errorMessage())
                .isEqualTo("Date is missing.");
    }

    @Test
    @DisplayName("UC-11: a date in the future is readable here, because the clock is not this class's rule")
    void aFutureDateIsReadableHere() {
        // BR-08 is enforced by sp_apply_csv_batch, not by the preview. A row dated 2099 previews as
        // importable and becomes an ERROR at the commit - which is the sequence the integration suite
        // pins, and the reason this assertion is here rather than a refusal.
        ImportRowDraft draft = read(row(Map.of(
                "date", "2099-01-01", "amount", "1.00", "type", "EXPENSE")));

        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.VALID);
        assertThat(draft.parsedDate()).isEqualTo(LocalDate.of(2099, 1, 1));
    }

    // ------------------------------------------------------------------
    //  Amounts
    // ------------------------------------------------------------------

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
        // DECIMAL(15,2). "12.5" and "12.50" are the same number, and normalising here means the value
        // the preview shows is the value the column will hold.
        ImportRowDraft draft = read(row(Map.of(
                "date", "2026-09-01", "amount", "12.5", "type", "EXPENSE")));

        assertThat(draft.parsedAmount()).isEqualByComparingTo("12.50");
        assertThat(draft.parsedAmount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("UC-11: a zero, a negative figure and a third decimal place are all refused")
    void amountsOutsideTheColumnsRulesAreRefused() {
        // ck_txn_amount requires a positive amount and the column allows two decimals. Refused in the
        // preview rather than at the commit, so the student learns about it once rather than one row at
        // a time as the procedure walks the file.
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
        // Thirteen integer digits fit; fourteen do not. Refused here so the value never reaches an
        // INSERT that would fail as a server error.
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

    // ------------------------------------------------------------------
    //  Types
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: the type is matched whatever its case")
    void theTypeIsMatchedCaseInsensitively() {
        // A spreadsheet happily writes "Expense" or "expense". The stored member is the enum's own.
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

    // ------------------------------------------------------------------
    //  Every problem in one message
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: a row with several problems reports all of them at once")
    void everyProblemOnARowIsReportedTogether() {
        // A student fixing a row should not have to upload three times to discover three mistakes. The
        // sentences are joined in the order the columns are read, so the message is stable.
        ImportRowDraft draft = read(row(Map.of("description", "nothing useful")));

        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.ERROR);
        assertThat(draft.errorMessage())
                .isEqualTo("Date is missing. Amount is missing. Type is missing.");
    }

    @Test
    @DisplayName("UC-11: a refused row keeps every value that did parse")
    void aRefusedRowKeepsWhatItCouldRead() {
        // The preview shows the interpretation beside the reason, rather than a row of blanks. The date
        // and the description were fine; only the amount was not.
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

    // ------------------------------------------------------------------
    //  Values cut to their columns
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: a description longer than its column is cut, not refused")
    void aLongDescriptionIsCutRatherThanRefused() {
        // Refusing would drop a real record - its amount, its date - because a note was long. The row
        // stays importable and the value is cut to the column, so the preview shows what will be stored.
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
        // Truncation alters only what had to be altered: a description at exactly the column's width is
        // stored whole rather than losing its last character to an off-by-one.
        String exact = "y".repeat(ImportRowReader.MAX_DESCRIPTION_LENGTH);

        assertThat(read(row(Map.of("date", "2026-09-01", "amount", "1.00", "type", "EXPENSE",
                "description", exact))).parsedDescription()).isEqualTo(exact);
    }

    // ------------------------------------------------------------------
    //  The stored line
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: the row is kept as a JSON object keyed by column")
    void theRawLineIsKeptAsJson() {
        // raw_data holds the line as it arrived, so the preview can show the student what the importer
        // read. Its keys are the column names rather than positions, so a client reads
        // raw_data.description rather than raw_data["3"].
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
        // raw_data is a JSON column, so a malformed object is a refused INSERT rather than a wrong
        // value. Interpolating the raw cell would corrupt the column for any description containing a
        // quote, which a spreadsheet produces without being asked.
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
        // The column convention ImportRowDraft records. A duplicate's note is written later, by the
        // detector, into the same column - so a reader that assumed "message means error" would be
        // wrong, but a reader of a draft may assume exactly this.
        ImportRowDraft valid = read(row(Map.of(
                "date", "2026-09-01", "amount", "1.00", "type", "EXPENSE")));
        ImportRowDraft refused = read(row(Map.of("date", "2026-09-01", "amount", "x", "type", "EXPENSE")));

        assertThat(valid.errorMessage()).isNull();
        assertThat(refused.errorMessage()).isNotNull();
    }

    // ------------------------------------------------------------------
    //  Fixtures
    // ------------------------------------------------------------------

    private ImportRowDraft read(CsvParser.CsvRecord record) {
        return reader.read(record);
    }

    /** A record as the parser would have produced it, starting on a data line. */
    private static CsvParser.CsvRecord row(Map<String, String> values) {
        return new CsvParser.CsvRecord(2, values);
    }
}
