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

/**
 * Decides what will happen to every row of a CSV file, and can decide it again after one row is
 * corrected (UC-11).
 *
 * <p><b>Two entry points, and the second one is what makes the preview editable rather than merely
 * informative.</b> {@link #preview} turns the student's file into a verdict for each of its rows;
 * {@link #verdicts} recomputes the duplicate verdicts for a batch that has already been stored. They
 * exist separately because their inputs are different shapes - a file on the way in, rows already in the
 * table on the way back - and not because the rule differs: both build the same
 * {@link ImportDuplicateDetector.Candidate} list and pass it to the same detector, so there is one
 * definition of "this row is already recorded" and the two entry points cannot disagree about it.
 *
 * <p><b>Why the second entry point has to exist at all, stated as the defect it prevents.</b> A row is a
 * duplicate when an earlier record shares its category, its amount and a nearby date - so the row's
 * category is one of the three facts the verdict rests on. {@code PATCH /imports/{batchId}/rows/{rowId}}
 * lets the student change exactly that fact, and a preview that did not re-decide would leave the row
 * excluded by a comparison against a category the student had just said was wrong. That is a silent
 * no-op on a screen whose whole purpose is to show what the import will do, and it would also mean a
 * row falsely flagged as a duplicate could never be rescued - the student would lose a record they have,
 * with no way to say so. So overriding a category re-runs the verdict, and a row can come back
 * corrected in either direction.
 *
 * <p><b>The category a row is compared under, stated once here.</b> A stored row's <em>authoritative</em>
 * category is {@code resolved_category_id} when the student has chosen one, and the file's own
 * {@code category} name resolved by type when they have not - which is exactly
 * {@code sp_apply_csv_batch}'s Case A and Case B, in the same order, so the row is compared under the
 * category the commit would actually file it under. A draft has no override yet - the file has just
 * arrived - so the name resolution is the only branch it can take. That asymmetry is why the two
 * candidate builders below are written apart rather than folded together.
 *
 * <p><b>The row cap lives here, and it is what bounds everything downstream.</b> UC-11's preview shows
 * every row of the file, and the response nests them, so the file's size is the size of every list in
 * this module: the stored rows, the detector's comparison set, the drafting loop. A file above
 * {@link #MAX_ROWS} is refused with a message about the file rather than accepted and truncated, because
 * a preview that showed part of a file would be showing the student something other than their file.
 * The character cap is the same argument for a file with few but enormous rows - the row count alone
 * does not bound the request.
 *
 * <p>Stateless, free of a repository and free of a transaction: it takes what the caller has read and
 * returns verdicts, the shape {@code AnomalyDetector} and {@code CategorySuggester} share. Everything it
 * decides is worked out from its arguments, so it is unit-tested with plain lists.
 */
@Component
public class ImportPreviewer {

    /**
     * The most rows a file may have.
     *
     * <p>Two thousand is well above any file a student's own records produce - a year of daily spending
     * is under four hundred rows - and well below the point where holding the file, its rows and its
     * response in memory at once is a concern. It is a refusal rather than a truncation: see the class
     * note.
     */
    static final int MAX_ROWS = 2000;

    /**
     * The most characters a file's content may have.
     *
     * <p>Roughly two megabytes of text. The row cap does not bound the request on its own - one row may
     * carry an arbitrarily long description - so a second bound is needed to keep the promise the first
     * one makes.
     */
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

    /**
     * A whole file as the preview decided it.
     *
     * <p>The three counts are computed here rather than by a caller, because they are properties of the
     * verdicts and a caller that recounted them could disagree with the rows it is about to store.
     * {@code totalRows} is every record the file held, including the ones that could not be read - a
     * student comparing the preview against their spreadsheet counts all of them.
     */
    public record PreviewedFile(
            List<ImportRowDraft> drafts,
            int totalRows,
            int validRows,
            int errorRows,
            int duplicateRows) {
    }

    /**
     * One stored row's duplicate verdict, recomputed.
     *
     * <p>{@code rowId} is carried so the caller can write the verdict back without a second lookup, and
     * {@code csvRowNo} so a log line or a test can name the file's line. {@code errorMessage} is null for
     * a row that will be imported, and carries the duplicate explanation for one that will not - the
     * same column convention {@code ImportRowDraft} records.
     */
    public record RowVerdict(long rowId, int csvRowNo, ImportRowStatus rowStatus, String errorMessage) {
    }

    /**
     * UC-11 A1/B5: reads a file and decides what will happen to each of its rows.
     *
     * <p>The order of the steps is the order of the questions, and it matters:
     *
     * <ol>
     *   <li>The file is parsed; a file that cannot be parsed at all is refused here, because there is no
     *       preview to show.</li>
     *   <li>Each record is read into a draft, valid or error. A bad row does not stop the file - see
     *       {@code ImportRowReader}.</li>
     *   <li>A suggestion is computed for each readable row, and stored beside it as a note. It never
     *       decides the row's category: BR-13 makes it advisory, and the row is filed under what the
     *       file said or what the student chooses.</li>
     *   <li>The duplicate check runs last, because it needs the category each row will be compared
     *       under - which is the suggestion's <em>separate</em> question, answered by the file's own
     *       category name rather than by what the system proposed.</li>
     * </ol>
     *
     * @param visible  the caller's own active categories, read once by the caller
     * @param rules    the caller's own learned mappings, so an import is suggested to by the same rules
     *                 a hand filing is
     * @param existing the caller's live records, which a row of the file may duplicate
     */
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

    /**
     * UC-11 B6: the duplicate verdicts for a batch that has already been stored, recomputed.
     *
     * <p>Called after the student chooses a category for one row, so it sees that choice in the row's
     * {@code resolved_category_id} and compares it - and every later row, whose own comparison set
     * includes it - under the corrected category.
     *
     * <p><b>A verdict is returned only for the rows whose duplicate question can be asked.</b> A row the
     * parser rejected is left out, because its explanation is about a value that does not parse and no
     * category choice changes that: recomputing it would either clear the message and claim the row is
     * importable, or restore a message that is still true. Rows already {@code IMPORTED} are left out for
     * the same reason - the batch they belong to is settled and nothing here runs for one that is not.
     * What remains is every row that is currently importable or currently excluded as a duplicate, which
     * is exactly the set whose verdict this method is being asked about.
     *
     * @return one verdict per row whose duplicate question applies, in the order the rows arrived
     */
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

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /**
     * The file's own size bounds, checked before anything is parsed.
     *
     * <p>Both are refusals with a field error on {@code content}, because both are things about the file
     * the student chose - and the message names the file rather than the limit, which is what they can
     * act on.
     */
    private static void requireUsableFile(String content) {
        if (content == null || content.isBlank()) {
            throw refusal("The file is empty.");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw refusal("The file is larger than this importer accepts. Split it into smaller files "
                    + "and upload them one at a time.");
        }
    }

    /**
     * The draft with the system's proposal attached, or unchanged when there is nothing to propose.
     *
     * <p>Only readable rows are asked about: a row whose amount could not be parsed has no description
     * worth sending anywhere, and asking a provider about it would be spending a request on a row that
     * cannot be imported whatever it answers.
     */
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

    /**
     * The draft as an excluded row, with the reason it was excluded.
     *
     * <p>The status changes and the suggestion is kept: the student is still shown what the system would
     * have proposed, and the row's own columns are untouched. What the duplicate doesn't change is the
     * amount, the date or the description - the row is well formed, it is simply already recorded.
     */
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

    /**
     * The rows to compare, built from the file on its way in.
     *
     * <p>Only readable rows are candidates - a row that could not be parsed has no amount or no date to
     * compare - and each one's category is the file's own name resolved by type, because a draft has no
     * override to prefer. A name that resolves to nothing yields a null category, which
     * {@code ImportDuplicateDetector} treats as matching nothing: the detector errs toward not flagging,
     * and a row that will fall back to a default category at the commit cannot be compared against a
     * category it does not have yet.
     */
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

    /**
     * The rows to compare, built from rows already stored.
     *
     * <p>Differs from the builder above in exactly one way: a row the student has already filed under a
     * chosen category is compared under <em>that</em> category, not under the name the file carried -
     * see the class note for why the commit would do the same. Everything else - which rows are
     * candidates, and that an unresolvable name means no category - is the same rule.
     */
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

    /**
     * The findings keyed by the file line they belong to.
     *
     * <p>A map rather than the list itself, because the caller asks "is this row a duplicate?" once per
     * row and a list would make that a scan of the list for each one. The key is the row's
     * {@code csv_row_no}, which the detector carries through from the candidate it was given.
     */
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
