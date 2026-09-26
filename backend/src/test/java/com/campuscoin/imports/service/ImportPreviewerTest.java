package com.campuscoin.imports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.common.setting.SettingReader;
import com.campuscoin.common.setting.repository.SystemSettingRepository;
import com.campuscoin.imports.entity.ImportRow;
import com.campuscoin.imports.entity.ImportRowDraft;
import com.campuscoin.imports.entity.ImportRowStatus;
import com.campuscoin.imports.service.ImportDuplicateDetector.Candidate;
import com.campuscoin.imports.service.ImportPreviewer.PreviewedFile;
import com.campuscoin.imports.service.ImportPreviewer.RowVerdict;

/**
 * The decisions {@link ImportPreviewer} makes about a whole file, tested directly.
 *
 * <p><b>Why this is a unit test, and what it therefore does not cover.</b> The previewer combines four
 * collaborators, three of which are pure and are tested in their own files. What is left here is its own
 * work: the two size bounds it enforces on the file, the row cap, and the counting that turns a list of
 * verdicts into the counters the preview screen shows. A file's size and a row count are numbers, so
 * each case is one string and one assertion - and the two caps are refusals rather than truncations,
 * which is exactly the kind of branch an upload exercises once and a table here exercises exhaustively.
 *
 * <p><b>{@code ImportCategoryResolver} is replaced rather than stubbed through a repository.</b> It is
 * the module's one door to {@code categories}, and reaching a real one would mean building
 * {@code Category} fixtures with ids - which module 3's entity does not allow: it has a protected no-arg
 * constructor and a factory for personal rows only, with no id setter. Replacing the resolver also
 * states the boundary this file tests: the previewer asks <em>what</em> a row's category is and does not
 * care how the answer was reached. The resolution rule itself is pinned by the integration suite, where
 * real categories exist.
 *
 * <p><b>The resolver's fake answers nothing by default.</b> Most rows in these fixtures name no category
 * or name one that does not resolve, which is the ordinary case for an import and the one that must not
 * produce a duplicate. Tests that need a duplicate opt in by naming {@code Food}.
 */
class ImportPreviewerTest {

    private static final long FOOD = 4L;

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 1);

    private static final String HEADER = "date,amount,type,description,category";

    private final ImportCategoryResolver resolver = mock(ImportCategoryResolver.class);

    private final ImportPreviewer previewer = new ImportPreviewer(
            new CsvParser(),
            new ImportRowReader(),
            resolver,
            new ImportDuplicateDetector(new SettingReader(settingRepository())));

    @BeforeEach
    void theResolverProposesNothingAndResolvesNothingByDefault() {
        when(resolver.resolveByName(anyList(), any(), any())).thenReturn(Optional.empty());
        when(resolver.suggestedCategoryId(anyList(), anyList(), any())).thenReturn(null);
    }

    // ------------------------------------------------------------------
    //  The file's size
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: a blank file is refused before it is parsed")
    void aBlankFileIsRefused() {
        for (String content : new String[]{"", "   ", null}) {
            assertThatThrownBy(() -> preview(content))
                    .as("content=%s", content)
                    .isInstanceOf(RequestValidationException.class)
                    .hasMessage("The file is empty.");
        }
    }

    @Test
    @DisplayName("UC-11: a file larger than the importer accepts is refused, not trimmed")
    void anOverlargeFileIsRefused() {
        // The row cap does not bound the request on its own - one row may carry an arbitrarily long
        // description - so the character cap is what makes the row cap's promise true. Trimming would
        // show the student a preview of something other than their file.
        String tooLong = "a".repeat(ImportPreviewer.MAX_CONTENT_LENGTH + 1);

        assertThatThrownBy(() -> preview(tooLong))
                .isInstanceOf(RequestValidationException.class)
                .hasMessage("The file is larger than this importer accepts. Split it into smaller "
                        + "files and upload them one at a time.");
    }

    @Test
    @DisplayName("UC-11: a file with a header but no rows is refused, because there is nothing to import")
    void aHeaderWithNoRowsIsRefused() {
        assertThatThrownBy(() -> preview(HEADER + "\n"))
                .isInstanceOf(RequestValidationException.class)
                .hasMessage("The file names its columns but has no rows of data, so there is nothing "
                        + "to import.");
    }

    @Test
    @DisplayName("UC-11: a file with more rows than the importer accepts is refused, and the cap holds")
    void tooManyRowsAreRefused() {
        // A preview that showed part of a file would be showing the student something other than their
        // file, so the cap is a refusal. The second assertion is the other half: a file of exactly the
        // cap is accepted, which is what proves the bound is not off by one.
        assertThatThrownBy(() -> preview(fileOf(ImportPreviewer.MAX_ROWS + 1)))
                .isInstanceOf(RequestValidationException.class)
                .hasMessage("The file has more than " + ImportPreviewer.MAX_ROWS + " rows. Split it "
                        + "into smaller files and upload them one at a time.");

        assertThat(preview(fileOf(ImportPreviewer.MAX_ROWS)).totalRows())
                .isEqualTo(ImportPreviewer.MAX_ROWS);
    }

    @Test
    @DisplayName("UC-11: every refusal about the file names the field the client sent")
    void everyFileRefusalNamesTheContentField() {
        // The file is what the client chose, so the error is attachable to the upload control rather
        // than being a form-level message with nowhere to render.
        assertThatThrownBy(() -> preview(""))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(thrown -> assertThat(((RequestValidationException) thrown).getFieldErrors())
                        .extracting(ApiError.FieldError::field)
                        .containsExactly("content"));
    }

    // ------------------------------------------------------------------
    //  Counting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: a file becomes a verdict per row, with the counters matching the verdicts")
    void theCountersMatchTheVerdicts() {
        // The counters are what the preview screen shows above the rows, so a row counted twice or not
        // at all would have the batch contradict its own list. One row of each verdict is the case that
        // catches a counter that is really a different counter.
        PreviewedFile preview = preview(fileOf(3) + """
                2026-09-01,oops,EXPENSE,bad amount,
                """);

        assertThat(preview.totalRows()).isEqualTo(4);
        assertThat(preview.validRows()).isEqualTo(3);
        assertThat(preview.errorRows()).isEqualTo(1);
        assertThat(preview.duplicateRows()).isZero();

        assertThat(preview.drafts()).extracting(ImportRowDraft::rowStatus)
                .containsExactly(ImportRowStatus.VALID, ImportRowStatus.VALID,
                        ImportRowStatus.VALID, ImportRowStatus.ERROR);
    }

    @Test
    @DisplayName("UC-11: a duplicate is counted as a duplicate and not as an error")
    void aDuplicateIsNotAnError() {
        // The two are different states with different remedies - one is a row the student can drop, the
        // other is a row they have to fix - and the schema gives them separate columns for exactly that
        // reason. A duplicate counted as an error would tell the student their file is broken.
        when(resolver.resolveByName(anyList(), eq("Food"), eq(CategoryType.EXPENSE)))
                .thenReturn(Optional.of(FOOD));

        PreviewedFile preview = preview(HEADER + "\n" + """
                2026-09-01,18.00,EXPENSE,Two coffees,Food
                2026-09-01,18.00,EXPENSE,Two coffees,Food
                """);

        assertThat(preview.totalRows()).isEqualTo(2);
        assertThat(preview.validRows()).isEqualTo(1);
        assertThat(preview.errorRows()).isZero();
        assertThat(preview.duplicateRows()).isEqualTo(1);
        assertThat(preview.drafts()).extracting(ImportRowDraft::rowStatus)
                .containsExactly(ImportRowStatus.VALID, ImportRowStatus.DUPLICATE);
    }

    @Test
    @DisplayName("UC-11: a duplicate keeps what the row will be filed under, and gains only the note")
    void aDuplicateGainsOnlyTheNote() {
        // What the duplicate does not change is the row's own columns: the row is well formed, it is
        // simply already recorded. The suggestion is kept too, so the student still sees what the system
        // would have proposed.
        when(resolver.resolveByName(anyList(), eq("Food"), eq(CategoryType.EXPENSE)))
                .thenReturn(Optional.of(FOOD));

        PreviewedFile preview = preview(HEADER + "\n" + """
                2026-09-01,18.00,EXPENSE,Campus cafe,Food
                2026-09-02,18.00,EXPENSE,Campus cafe,Food
                """);

        ImportRowDraft duplicate = preview.drafts().get(1);
        assertThat(duplicate.rowStatus()).isEqualTo(ImportRowStatus.DUPLICATE);
        assertThat(duplicate.parsedCategoryName()).isEqualTo("Food");
        assertThat(duplicate.parsedAmount()).isEqualByComparingTo("18.00");
        assertThat(duplicate.parsedDate()).isEqualTo(BASE_DATE.plusDays(1));
        assertThat(duplicate.errorMessage())
                .as("the note is written into the row's one free-text column")
                .contains("You already have a record of 18.00")
                .contains(BASE_DATE.toString());
    }

    @Test
    @DisplayName("UC-11: a row the reader refused is never asked about a duplicate")
    void anUnreadableRowIsNotCheckedForDuplicates() {
        // A row whose amount did not parse has nothing to compare, and its explanation is about the
        // value rather than about the student's history. Asking would either overwrite that explanation
        // with a note about a row that cannot be imported anyway.
        PreviewedFile preview = preview(HEADER + "\n" + """
                2026-09-01,oops,EXPENSE,Campus cafe,Food
                """);

        assertThat(preview.drafts().getFirst().rowStatus()).isEqualTo(ImportRowStatus.ERROR);
        assertThat(preview.drafts().getFirst().errorMessage())
                .isEqualTo("Amount must be a positive number, with at most 2 decimal places.");
        assertThat(preview.errorRows()).isEqualTo(1);
        assertThat(preview.duplicateRows()).isZero();
    }

    @Test
    @DisplayName("UC-11: the drafts keep the file's order and its line numbers")
    void theDraftsKeepTheFilesOrder() {
        // The rows are stored in file order and read back by csv_row_no, so the preview's own list has
        // to carry the same order and the same numbers the parser gave it.
        PreviewedFile preview = preview(fileOf(3));

        assertThat(preview.drafts()).extracting(ImportRowDraft::csvRowNo)
                .containsExactly(2, 3, 4);
        assertThat(preview.drafts()).extracting(ImportRowDraft::parsedDate)
                .containsExactly(BASE_DATE, BASE_DATE.plusDays(1), BASE_DATE.plusDays(2));
    }

    // ------------------------------------------------------------------
    //  The suggestion is a note, never a filing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: the system's proposal is stored beside the row and never decides its category")
    void theSuggestionIsANoteAndNotADecision() {
        // BR-13. The row is filed under what the file said; the proposal is carried in a separate column
        // so the preview can show it. A suggestion that wrote itself into the row's category would turn
        // advice into a decision the student never made.
        when(resolver.suggestedCategoryId(anyList(), anyList(), eq("Campus cafe latte")))
                .thenReturn(FOOD);

        PreviewedFile preview = preview(HEADER + "\n" + """
                2026-09-01,18.00,EXPENSE,Campus cafe latte,Transport
                """);

        ImportRowDraft draft = preview.drafts().getFirst();
        assertThat(draft.aiSuggestedCategoryId()).isEqualTo(FOOD);
        assertThat(draft.parsedCategoryName()).isEqualTo("Transport");
        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.VALID);
    }

    @Test
    @DisplayName("UC-11: an empty description is a row the resolver is asked about, not one skipped here")
    void anEmptyDescriptionIsHandedToTheResolver() {
        // The rule that an empty description is never sent to a provider lives in ImportCategoryResolver,
        // not here - this class asks about every readable row and lets the resolver's own guard answer.
        // Asserting the delegation rather than reproducing the guard keeps one definition of "nothing to
        // categorise by", and pins that the row is importable regardless: a description is optional.
        PreviewedFile preview = preview(HEADER + "\n" + """
                2026-09-01,18.00,EXPENSE,,
                """);

        assertThat(preview.drafts().getFirst().rowStatus()).isEqualTo(ImportRowStatus.VALID);
        assertThat(preview.drafts().getFirst().aiSuggestedCategoryId()).isNull();
        verify(resolver).suggestedCategoryId(anyList(), anyList(), isNull());
    }

    @Test
    @DisplayName("UC-11: a row the reader refused is never sent anywhere for a proposal either")
    void anUnreadableRowIsNotProposed() {
        preview(HEADER + "\n" + """
                2026-09-01,oops,EXPENSE,Campus cafe,
                """);

        verify(resolver, never()).suggestedCategoryId(anyList(), anyList(), any());
    }

    // ------------------------------------------------------------------
    //  Re-deciding a stored row
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: a stored row that now duplicates a record is re-verdict as a duplicate")
    void aStoredRowThatNowDuplicatesIsFlagged() {
        // The PATCH endpoint's whole purpose. A row is a duplicate when an earlier record shares its
        // category, its amount and a nearby date - so the category the student just changed is one of
        // the three facts the verdict rests on, and the verdict has to be asked again.
        List<RowVerdict> verdicts = previewer.verdicts(
                List.of(storedRow(7, 3, FOOD, "75.00", BASE_DATE, ImportRowStatus.VALID, null)),
                List.of(),
                List.of(existing(FOOD, "75.00", BASE_DATE)));

        assertThat(verdicts).singleElement().satisfies(verdict -> {
            assertThat(verdict.rowId()).isEqualTo(7);
            assertThat(verdict.csvRowNo()).isEqualTo(3);
            assertThat(verdict.rowStatus()).isEqualTo(ImportRowStatus.DUPLICATE);
            assertThat(verdict.errorMessage()).contains("You already have a record of 75.00");
        });
    }

    @Test
    @DisplayName("UC-11: a duplicate the student has since moved is re-verdict as importable")
    void aStoredDuplicateThatNoLongerDuplicatesIsFreed() {
        // The other direction, and the one that matters more: without it a row falsely flagged could
        // never be rescued, and the student would lose a record they have with no way to say so. A
        // cleared verdict carries no note, which is the column convention.
        List<RowVerdict> verdicts = previewer.verdicts(
                List.of(storedRow(7, 3, FOOD, "75.00", BASE_DATE, ImportRowStatus.DUPLICATE, "the old note")),
                List.of(),
                List.of(existing(FOOD, "75.00", BASE_DATE.plusDays(30))));

        assertThat(verdicts).singleElement().satisfies(verdict -> {
            assertThat(verdict.rowStatus()).isEqualTo(ImportRowStatus.VALID);
            assertThat(verdict.errorMessage()).isNull();
        });
    }

    @Test
    @DisplayName("UC-11: a row already imported or already refused is not re-verdict")
    void onlyDecidableRowsGetAVerdict() {
        // An ERROR row's explanation is about a value that does not parse and no category choice changes
        // that: recomputing it would either clear a message that is still true or claim the row is
        // importable. An IMPORTED row belongs to a settled batch, so nothing here runs for it.
        List<RowVerdict> verdicts = previewer.verdicts(
                List.of(storedRow(1, 2, FOOD, "10.00", BASE_DATE, ImportRowStatus.ERROR, "Amount is missing."),
                        storedRow(2, 3, FOOD, "20.00", BASE_DATE, ImportRowStatus.IMPORTED, null),
                        storedRow(3, 4, FOOD, "30.00", BASE_DATE, ImportRowStatus.VALID, null)),
                List.of(),
                List.of());

        assertThat(verdicts).extracting(RowVerdict::rowId).containsExactly(3L);
    }

    @Test
    @DisplayName("UC-11: a stored row is compared under the category the student chose, not the file's name")
    void aStoredRowIsComparedUnderItsChosenCategory() {
        // The asymmetry that makes the two entry points differ. A draft has no override yet, so it can
        // only use the file's name; a stored row has one, and comparing it under the name the file
        // carried would compare it against a category the student has just said was wrong.
        List<RowVerdict> verdicts = previewer.verdicts(
                List.of(storedRow(7, 3, FOOD, "75.00", BASE_DATE, ImportRowStatus.VALID, null)),
                List.of(),
                List.of(existing(FOOD, "75.00", BASE_DATE)));

        assertThat(verdicts.getFirst().rowStatus()).isEqualTo(ImportRowStatus.DUPLICATE);
        verify(resolver, never()).resolveByName(anyList(), any(), any());
    }

    @Test
    @DisplayName("UC-11: a stored row with no chosen category falls back to the name the file carried")
    void aStoredRowWithoutAChoiceResolvesItsName() {
        // A row the student has not corrected still has to be compared under whatever the commit would
        // file it under, which is the file's own name resolved by type.
        when(resolver.resolveByName(anyList(), eq("Food"), eq(CategoryType.EXPENSE)))
                .thenReturn(Optional.of(FOOD));

        List<RowVerdict> verdicts = previewer.verdicts(
                List.of(new ImportRow(7L, 3, null, BASE_DATE, new BigDecimal("75.00"),
                        CategoryType.EXPENSE, "textbook", "Food", null, null,
                        ImportRowStatus.VALID, null, null)),
                List.of(),
                List.of(existing(FOOD, "75.00", BASE_DATE)));

        assertThat(verdicts.getFirst().rowStatus()).isEqualTo(ImportRowStatus.DUPLICATE);
    }

    // ------------------------------------------------------------------
    //  Fixtures
    // ------------------------------------------------------------------

    private PreviewedFile preview(String content) {
        return previewer.preview(content, List.of(), List.of(), List.of());
    }

    /** A file of well-formed, non-matching rows: one per day, each a different amount. */
    private static String fileOf(int rows) {
        StringBuilder content = new StringBuilder(HEADER).append('\n');
        for (int index = 0; index < rows; index++) {
            content.append(BASE_DATE.plusDays(index)).append(',').append(10 + index).append(".00")
                    .append(",EXPENSE,row ").append(index).append(",\n");
        }
        return content.toString();
    }

    private static Candidate existing(long categoryId, String amount, LocalDate date) {
        return new Candidate(0, categoryId, new BigDecimal(amount), date);
    }

    private static ImportRow storedRow(long rowId, int csvRowNo, Long categoryId, String amount,
                                       LocalDate date, ImportRowStatus status, String message) {
        return new ImportRow(rowId, csvRowNo, null, date, new BigDecimal(amount), CategoryType.EXPENSE,
                "campus cafe", null, categoryId, null, status, message, null);
    }

    private static SystemSettingRepository settingRepository() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.findBySettingKey(any())).thenReturn(Optional.empty());
        return repository;
    }
}
