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

        assertThatThrownBy(() -> preview(""))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(thrown -> assertThat(((RequestValidationException) thrown).getFieldErrors())
                        .extracting(ApiError.FieldError::field)
                        .containsExactly("content"));
    }

    @Test
    @DisplayName("UC-11: a file becomes a verdict per row, with the counters matching the verdicts")
    void theCountersMatchTheVerdicts() {

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

        PreviewedFile preview = preview(HEADER + "\n" + """
                2026-09-01,oops,EXPENSE,Campus cafe,Food
                """);

        assertThat(preview.drafts().get(0).rowStatus()).isEqualTo(ImportRowStatus.ERROR);
        assertThat(preview.drafts().get(0).errorMessage())
                .isEqualTo("Amount must be a positive number, with at most 2 decimal places.");
        assertThat(preview.errorRows()).isEqualTo(1);
        assertThat(preview.duplicateRows()).isZero();
    }

    @Test
    @DisplayName("UC-11: the drafts keep the file's order and its line numbers")
    void theDraftsKeepTheFilesOrder() {

        PreviewedFile preview = preview(fileOf(3));

        assertThat(preview.drafts()).extracting(ImportRowDraft::csvRowNo)
                .containsExactly(2, 3, 4);
        assertThat(preview.drafts()).extracting(ImportRowDraft::parsedDate)
                .containsExactly(BASE_DATE, BASE_DATE.plusDays(1), BASE_DATE.plusDays(2));
    }

    @Test
    @DisplayName("UC-11: the system's proposal is stored beside the row and never decides its category")
    void theSuggestionIsANoteAndNotADecision() {

        when(resolver.suggestedCategoryId(anyList(), anyList(), eq("Campus cafe latte")))
                .thenReturn(FOOD);

        PreviewedFile preview = preview(HEADER + "\n" + """
                2026-09-01,18.00,EXPENSE,Campus cafe latte,Transport
                """);

        ImportRowDraft draft = preview.drafts().get(0);
        assertThat(draft.aiSuggestedCategoryId()).isEqualTo(FOOD);
        assertThat(draft.parsedCategoryName()).isEqualTo("Transport");
        assertThat(draft.rowStatus()).isEqualTo(ImportRowStatus.VALID);
    }

    @Test
    @DisplayName("UC-11: an empty description is a row the resolver is asked about, not one skipped here")
    void anEmptyDescriptionIsHandedToTheResolver() {

        PreviewedFile preview = preview(HEADER + "\n" + """
                2026-09-01,18.00,EXPENSE,,
                """);

        assertThat(preview.drafts().get(0).rowStatus()).isEqualTo(ImportRowStatus.VALID);
        assertThat(preview.drafts().get(0).aiSuggestedCategoryId()).isNull();
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

    @Test
    @DisplayName("UC-11: a stored row that now duplicates a record is re-verdict as a duplicate")
    void aStoredRowThatNowDuplicatesIsFlagged() {

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

        List<RowVerdict> verdicts = previewer.verdicts(
                List.of(storedRow(7, 3, FOOD, "75.00", BASE_DATE, ImportRowStatus.VALID, null)),
                List.of(),
                List.of(existing(FOOD, "75.00", BASE_DATE)));

        assertThat(verdicts.get(0).rowStatus()).isEqualTo(ImportRowStatus.DUPLICATE);
        verify(resolver, never()).resolveByName(anyList(), any(), any());
    }

    @Test
    @DisplayName("UC-11: a stored row with no chosen category falls back to the name the file carried")
    void aStoredRowWithoutAChoiceResolvesItsName() {

        when(resolver.resolveByName(anyList(), eq("Food"), eq(CategoryType.EXPENSE)))
                .thenReturn(Optional.of(FOOD));

        List<RowVerdict> verdicts = previewer.verdicts(
                List.of(new ImportRow(7L, 3, null, BASE_DATE, new BigDecimal("75.00"),
                        CategoryType.EXPENSE, "textbook", "Food", null, null,
                        ImportRowStatus.VALID, null, null)),
                List.of(),
                List.of(existing(FOOD, "75.00", BASE_DATE)));

        assertThat(verdicts.get(0).rowStatus()).isEqualTo(ImportRowStatus.DUPLICATE);
    }

    private PreviewedFile preview(String content) {
        return previewer.preview(content, List.of(), List.of(), List.of());
    }

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
