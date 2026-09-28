package com.campuscoin.imports.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.campuscoin.categorisation.entity.CategoryRuleRow;
import com.campuscoin.category.entity.Category;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.imports.entity.ImportRow;
import com.campuscoin.imports.entity.ImportRowDraft;
import com.campuscoin.imports.entity.ImportRowStatus;
import com.campuscoin.imports.service.ImportDuplicateDetector.Candidate;
import com.campuscoin.imports.service.ImportDuplicateDetector.Finding;

@Component
public class ImportPreviewer {

    static final int MAX_ROWS = 2000;

    static final int MAX_CONTENT_LENGTH = 2_000_000;

    private final CsvParser csvParser;
    private final ImportRowReader importRowReader;
    private final ImportCategoryResolver importCategoryResolver;
    private final ImportDuplicateDetector importDuplicateDetector;

    public ImportPreviewer(CsvParser csvParser,
                           ImportRowReader importRowReader,
                           ImportCategoryResolver importCategoryResolver,
                           ImportDuplicateDetector importDuplicateDetector) {
        this.csvParser = csvParser;
        this.importRowReader = importRowReader;
        this.importCategoryResolver = importCategoryResolver;
        this.importDuplicateDetector = importDuplicateDetector;
    }

    public record PreviewedFile(
            List<ImportRowDraft> drafts,
            int totalRows,
            int validRows,
            int errorRows,
            int duplicateRows) {
    }

    public record RowVerdict(long rowId, int csvRowNo, ImportRowStatus rowStatus, String errorMessage) {
    }

    public PreviewedFile preview(String content, List<Category> visible, List<CategoryRuleRow> rules,
                                 List<Candidate> existing) {
        requireUsableFile(content);

        CsvParser.CsvDocument document = csvParser.parse(content);
        if (document.records().isEmpty()) {
            throw refusal("The file names its columns but has no rows of data, so there is nothing to "
                    + "import.");
        }
        if (document.records().size() > MAX_ROWS) {
            throw refusal("The file has more than " + MAX_ROWS + " rows. Split it into smaller files "
                    + "and upload them one at a time.");
        }

        List<ImportRowDraft> drafts = new ArrayList<>(document.records().size());
        for (CsvParser.CsvRecord record : document.records()) {
            drafts.add(withSuggestion(importRowReader.read(record), visible, rules));
        }

        Map<Integer, Finding> duplicates = detect(existing, candidatesFromDrafts(drafts, visible));

        List<ImportRowDraft> decided = new ArrayList<>(drafts.size());
        int valid = 0;
        int errors = 0;
        int duplicateRows = 0;

        for (ImportRowDraft draft : drafts) {
            Finding finding = draft.rowStatus() == ImportRowStatus.VALID
                    ? duplicates.get(draft.csvRowNo())
                    : null;

            if (finding != null) {
                decided.add(asDuplicate(draft, finding.note()));
                duplicateRows++;
            } else {
                decided.add(draft);
                if (draft.rowStatus() == ImportRowStatus.ERROR) {
                    errors++;
                } else {
                    valid++;
                }
            }
        }

        return new PreviewedFile(List.copyOf(decided), decided.size(), valid, errors, duplicateRows);
    }

    public List<RowVerdict> verdicts(List<ImportRow> rows, List<Category> visible,
                                     List<Candidate> existing) {
        List<ImportRow> decidable = rows.stream()
                .filter(row -> row.rowStatus() == ImportRowStatus.VALID
                        || row.rowStatus() == ImportRowStatus.DUPLICATE)
                .toList();

        Map<Integer, Finding> duplicates = detect(existing, candidatesFromRows(decidable, visible));

        List<RowVerdict> verdicts = new ArrayList<>(decidable.size());
        for (ImportRow row : decidable) {
            Finding finding = duplicates.get(row.csvRowNo());
            verdicts.add(finding == null
                    ? new RowVerdict(row.rowId(), row.csvRowNo(), ImportRowStatus.VALID, null)
                    : new RowVerdict(row.rowId(), row.csvRowNo(), ImportRowStatus.DUPLICATE,
                            finding.note()));
        }

        return List.copyOf(verdicts);
    }

    private static void requireUsableFile(String content) {
        if (content == null || content.isBlank()) {
            throw refusal("The file is empty.");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw refusal("The file is larger than this importer accepts. Split it into smaller files "
                    + "and upload them one at a time.");
        }
    }

    private ImportRowDraft withSuggestion(ImportRowDraft draft, List<Category> visible,
                                          List<CategoryRuleRow> rules) {
        if (draft.rowStatus() != ImportRowStatus.VALID) {
            return draft;
        }

        Long suggested = importCategoryResolver.suggestedCategoryId(
                visible, rules, draft.parsedDescription());
        if (suggested == null) {
            return draft;
        }

        return new ImportRowDraft(
                draft.csvRowNo(),
                draft.rawData(),
                draft.parsedDate(),
                draft.parsedAmount(),
                draft.parsedType(),
                draft.parsedDescription(),
                draft.parsedCategoryName(),
                suggested,
                draft.rowStatus(),
                draft.errorMessage());
    }

    private static ImportRowDraft asDuplicate(ImportRowDraft draft, String note) {
        return new ImportRowDraft(
                draft.csvRowNo(),
                draft.rawData(),
                draft.parsedDate(),
                draft.parsedAmount(),
                draft.parsedType(),
                draft.parsedDescription(),
                draft.parsedCategoryName(),
                draft.aiSuggestedCategoryId(),
                ImportRowStatus.DUPLICATE,
                note);
    }

    private List<Candidate> candidatesFromDrafts(List<ImportRowDraft> drafts, List<Category> visible) {
        List<Candidate> candidates = new ArrayList<>(drafts.size());
        for (ImportRowDraft draft : drafts) {
            if (draft.rowStatus() != ImportRowStatus.VALID) {
                continue;
            }
            candidates.add(new Candidate(
                    draft.csvRowNo(),
                    importCategoryResolver
                            .resolveByName(visible, draft.parsedCategoryName(), draft.parsedType())
                            .orElse(null),
                    draft.parsedAmount(),
                    draft.parsedDate()));
        }
        return candidates;
    }

    private List<Candidate> candidatesFromRows(List<ImportRow> rows, List<Category> visible) {
        List<Candidate> candidates = new ArrayList<>(rows.size());
        for (ImportRow row : rows) {
            Long categoryId = row.resolvedCategoryId() != null
                    ? row.resolvedCategoryId()
                    : importCategoryResolver
                            .resolveByName(visible, row.parsedCategoryName(), row.parsedType())
                            .orElse(null);

            candidates.add(new Candidate(row.csvRowNo(), categoryId, row.parsedAmount(),
                    row.parsedDate()));
        }
        return candidates;
    }

    private Map<Integer, Finding> detect(List<Candidate> existing, List<Candidate> fileRows) {
        Map<Integer, Finding> byLine = new HashMap<>();
        for (Finding finding : importDuplicateDetector.detect(existing, fileRows)) {
            byLine.put(finding.lineNumber(), finding);
        }
        return byLine;
    }

    private static RequestValidationException refusal(String message) {
        return new RequestValidationException(message, List.of(new ApiError.FieldError("content", message)));
    }
}
