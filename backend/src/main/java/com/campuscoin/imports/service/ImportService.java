package com.campuscoin.imports.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.auth.security.TokenHashService;
import com.campuscoin.categorisation.entity.CategoryRuleRow;
import com.campuscoin.categorisation.repository.CategoryRuleDao;
import com.campuscoin.category.entity.Category;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.DataConflictException;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.imports.dto.ImportBatchListResponse;
import com.campuscoin.imports.dto.ImportBatchResponse;
import com.campuscoin.imports.dto.ImportRowResponse;
import com.campuscoin.imports.dto.SetImportRowCategoryRequest;
import com.campuscoin.imports.dto.UploadCsvRequest;
import com.campuscoin.imports.entity.ImportBatchRow;
import com.campuscoin.imports.entity.ImportRow;
import com.campuscoin.imports.mapper.ImportMapper;
import com.campuscoin.imports.repository.ImportProcedureDao;
import com.campuscoin.imports.repository.ImportViewDao;
import com.campuscoin.imports.repository.ImportWriteDao;
import com.campuscoin.imports.service.ImportDuplicateDetector.Candidate;
import com.campuscoin.imports.service.ImportPreviewer.PreviewedFile;
import com.campuscoin.imports.service.ImportPreviewer.RowVerdict;

/**
 * The CSV import: preview, correct, commit, abandon (UC-11).
 *
 * <p><b>The division of labour, because this class is where the module's pieces meet.</b> Everything
 * that could be worked out from data alone is in a collaborator that holds no transaction:
 * {@code CsvParser} reads the file, {@code ImportRowReader} reads each row, {@code ImportPreviewer}
 * decides what will happen to each one, {@code ImportRuleLearner} teaches what the commit established.
 * This class owns the three things that need the database, and owns them in this order:
 * <em>read what the check needs, decide, write one transaction.</em> It is deliberately thin - a
 * reader looking for a rule about duplicates, categories or the commit will not find it here, and that
 * is the point.
 *
 * <p><b>Ownership is established by the reads, and that is the mechanism rather than a formality.</b>
 * Every batch this class touches is read through {@code ImportViewDao} with the caller's id as a
 * predicate, so another student's batch is indistinguishable from one that does not exist
 * (section 7.5). The commit is the case where that matters most: {@code sp_apply_csv_batch} takes only
 * a batch id and cannot know who is calling, so the ownership check that stops it committing another
 * student's file is the {@link #requireOpenBatch} read that precedes it. The write statements carry
 * their own join as well, so the guarantee survives a caller that reached one of them some other way.
 *
 * <p><b>What the database owns, and this class therefore does not restate.</b>
 *
 * <ul>
 *   <li><b>Which rows become transactions and under which category</b> -
 *       {@code sp_apply_csv_batch}'s whole body. The student's override is authoritative there, the
 *       file's name is resolved only when there is none, and the type's default is the last resort.
 *       {@link ImportPreviewer} mirrors the same order for the duplicate comparison and nowhere else;
 *       nothing here re-derives a category.</li>
 *   <li><b>Which rows a commit will attempt</b> - the procedure walks {@code row_status = 'VALID'},
 *       which is {@code ImportRowStatus#isImportable}. A duplicate and an error are both left alone,
 *       which is why the preview has to distinguish them.</li>
 *   <li><b>The counters after a commit</b> - the procedure rewrites all five from its own walk and
 *       marks the batch {@code COMMITTED} in the same statement. Nothing here computes an imported
 *       count: the response reads the batch back.</li>
 *   <li><b>That one bad row does not end a batch</b> - each row is inserted inside its own handler, so
 *       a future date (BR-08) or a retired category (BR-07) becomes an {@code ERROR} row and the walk
 *       continues. A Java-side per-row transaction would produce a partial batch instead, which is the
 *       opposite of what the procedure does.</li>
 * </ul>
 *
 * <p><b>Every response is read back from the table, and none is assembled from the request.</b> The
 * upload returns the batch the transaction just wrote; the override returns the stored row; the commit
 * returns the batch and rows as the procedure left them. So a response cannot describe a state the
 * database does not hold, and the counter the student sees is the counter the table stores - the
 * reasoning {@code RecentActivityService} records for the same arrangement.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    /**
     * The number of imports the list returns when the caller does not name one.
     *
     * <p>Twenty, matching {@code FlaggedTransactionListResponse}'s bound rather than the recent
     * activity list's ten: this list is a history a student consults occasionally, not a screenful of
     * what they just did, so a longer page is the more useful default.
     */
    static final int DEFAULT_LIST_LIMIT = 20;

    /** The largest list this endpoint will return. Refused rather than clamped - see {@link #effectiveLimit}. */
    static final int MAX_LIST_LIMIT = 100;

    /**
     * The most files one student may have stored.
     *
     * <p>UC-11 has no delete, so this list only grows, and a bound is what keeps it a list of imports
     * rather than an archive. Three hundred is several years of monthly statements with room to spare;
     * it is refused with a message naming the limit rather than clamping the read, because a student
     * who is at the bound has to be told that, and a silently trimmed answer would look exactly like a
     * short history.
     */
    static final int MAX_BATCHES = 300;

    /** The largest array the JSON column can hold is not a concern here; the row cap in the previewer is. */
    private final ImportPreviewer importPreviewer;
    private final ImportCategoryResolver importCategoryResolver;
    private final ImportViewDao importViewDao;
    private final ImportWriteDao importWriteDao;
    private final ImportProcedureDao importProcedureDao;
    private final ImportRuleLearner importRuleLearner;
    private final CategoryRuleDao categoryRuleDao;
    private final ImportMapper importMapper;
    private final TokenHashService tokenHashService;

    public ImportService(ImportPreviewer importPreviewer,
                         ImportCategoryResolver importCategoryResolver,
                         ImportViewDao importViewDao,
                         ImportWriteDao importWriteDao,
                         ImportProcedureDao importProcedureDao,
                         ImportRuleLearner importRuleLearner,
                         CategoryRuleDao categoryRuleDao,
                         ImportMapper importMapper,
                         TokenHashService tokenHashService) {
        this.importPreviewer = importPreviewer;
        this.importCategoryResolver = importCategoryResolver;
        this.importViewDao = importViewDao;
        this.importWriteDao = importWriteDao;
        this.importProcedureDao = importProcedureDao;
        this.importRuleLearner = importRuleLearner;
        this.categoryRuleDao = categoryRuleDao;
        this.importMapper = importMapper;
        this.tokenHashService = tokenHashService;
    }

    /**
     * UC-11 A1: parses a file, previews it, and stores the batch with its rows.
     *
     * <p><b>The whole upload is one transaction, which is what makes "the file either becomes a
     * preview or nothing was stored" true.</b> The batch row, its rows and its counters are written
     * together; a file that turns out to be unusable is refused before the first write, because
     * {@link ImportPreviewer#preview} decides everything from the file's text and throws rather than
     * returning a partial result. So there is no half-built batch and no {@code FAILED} state to reach
     * - the schema has the member, and this path cannot produce one.
     *
     * <p><b>The batch is created {@code PREVIEWED} rather than {@code UPLOADED}</b>, because by the
     * time the transaction commits the rows are stored and the response describes them. See
     * {@code ImportWriteDao} for why the column's default is not the honest value here.
     *
     * <p><b>Every read the preview needs happens before the first write</b>, and there are three: the
     * caller's visible categories, their learned rules, and their live records for the duplicate check.
     * Each is one query for the whole file rather than one per row.
     *
     * <p>The file's size is bounded by {@link ImportPreviewer} - both its row count and its length -
     * and the per-student batch count is bounded here. Both are refusals: see {@link #MAX_BATCHES}.
     */
    @Transactional
    public ImportBatchResponse upload(AuthenticatedUser principal, UploadCsvRequest request) {
        Long userId = principal.userId();

        if (importViewDao.findBatches(userId, MAX_BATCHES).size() >= MAX_BATCHES) {
            throw new RequestValidationException(
                    "You have reached the most imports this account can hold.",
                    List.of(new ApiError.FieldError("content",
                            "This account already has " + MAX_BATCHES + " imports. They cannot be "
                                    + "deleted, so please use one of the imports you already have.")));
        }

        List<Category> visible = importCategoryResolver.visibleCategories(userId);
        List<CategoryRuleRow> rules = categoryRuleDao.findRules(userId);
        List<Candidate> existing = importViewDao.findExistingRecords(userId);

        PreviewedFile preview = importPreviewer.preview(request.content(), visible, rules, existing);

        long batchId = importWriteDao.createBatch(
                userId,
                request.filename(),
                tokenHashService.sha256Hex(request.content()),
                preview.totalRows(),
                preview.validRows(),
                preview.errorRows(),
                preview.duplicateRows());

        importWriteDao.insertRows(batchId, preview.drafts());

        log.info("CSV import previewed userId={} batchId={} rows={} valid={} errors={} duplicates={}",
                userId, batchId, preview.totalRows(), preview.validRows(), preview.errorRows(),
                preview.duplicateRows());

        return readBatch(userId, batchId);
    }

    /**
     * UC-11: the caller's imports, newest first.
     *
     * <p>No {@code 404} for an empty list: a student who has never imported a file has an empty
     * history, which is a fact about their own data rather than a missing resource.
     */
    @Transactional(readOnly = true)
    public ImportBatchListResponse list(AuthenticatedUser principal, int limit) {
        int applied = effectiveLimit(limit);
        List<ImportBatchRow> batches = importViewDao.findBatches(principal.userId(), applied);

        return importMapper.toListResponse(applied, batches);
    }

    /**
     * UC-11 B5: one of the caller's batches with its rows, in file order.
     *
     * <p>This is the preview screen as an endpoint: the counters, the status, whether the rows can
     * still be changed, and every row with what will happen to it. One batch, one request, because the
     * screen is one screen.
     */
    @Transactional(readOnly = true)
    public ImportBatchResponse get(AuthenticatedUser principal, Long batchId) {
        return readBatch(principal.userId(), batchId);
    }

    /**
     * UC-11 B6: files one previewed row under the category the student chose.
     *
     * <p><b>The two checks on the category, and why both are needed.</b> {@code findVisibleById}
     * deliberately does not filter {@code is_active}, so it answers "is this category one of the
     * caller's own or a shared default" and says nothing about whether it is retired - and a retired
     * category is exactly the one this must refuse (BR-07). So the lookup answers the ownership
     * question with a {@code 404} that does not reveal whose category it is, and the active check
     * answers the retirement question with a field error naming the picker. {@code TransactionService}
     * applies the same two checks in the same order for a new record, and the reason they are mirrored
     * rather than shared is that they guard a different table's write: this one is followed by
     * {@code ImportWriteDao}'s statement, whose own join is a second, independent guard.
     *
     * <p><b>The duplicate verdict is recomputed and written back, and that is not bookkeeping.</b> A
     * row is a duplicate when an earlier record shares its category, its amount and a nearby date, so
     * the category the student just changed is one of the three facts the verdict rests on.
     * {@link ImportPreviewer#verdicts} re-asks the question for every row whose answer could have
     * moved, and this writes each new answer back - which is what lets a row the student corrected
     * become importable again, or a row that has just become a duplicate of another be flagged before
     * the commit rather than discovered after it.
     *
     * <p>Rows the parser rejected are deliberately not re-verdicts: their explanation is about a value
     * that does not parse, no category choice changes it, and rewriting it would clear a message that
     * is still true. {@code ImportPreviewer} excludes them, so nothing here can.
     *
     * <p>The response is the stored row, read after both writes, so it carries the corrected category
     * and the corrected verdict together.
     */
    @Transactional
    public ImportRowResponse setRowCategory(AuthenticatedUser principal, Long batchId, Long rowId,
                                            SetImportRowCategoryRequest request) {
        Long userId = principal.userId();

        ImportBatchRow batch = requireOpenBatch(userId, batchId);

        Category category = importCategoryResolver.findVisible(userId, request.categoryId())
                .orElseThrow(() -> new NotFoundException("Category not found."));
        requireActiveCategory(category);

        if (importWriteDao.setResolvedCategory(userId, batchId, rowId, category.getId()) == 0) {
            // Zero covers "not your row", "no such row" and "the batch was settled by a concurrent
            // request". The first two are answered as the same missing row, and the third is answered
            // by the read below, which reports the status the table actually holds.
            importViewDao.findRow(userId, batchId, rowId)
                    .orElseThrow(() -> new NotFoundException("Import row not found."));
            throw new DataConflictException(
                    "This import is no longer open. Reload it to see its current state.");
        }

        applyVerdicts(userId, batchId);

        log.info("CSV import row filed userId={} batchId={} rowId={} categoryId={}",
                userId, batchId, rowId, category.getId());

        ImportRow row = importViewDao.findRow(userId, batchId, rowId)
                .orElseThrow(() -> new IllegalStateException(
                        "Import row " + rowId + " was not readable back after being filed; batch "
                                + batch.batchId() + " was open when it was written."));

        return importMapper.toRowResponse(row);
    }

    /**
     * UC-11 B9: imports the batch - generates a transaction for every importable row.
     *
     * <p><b>The read before the call is the ownership check, and it is the only one there is.</b> The
     * procedure reads the batch's owner from the batch itself and cannot compare it with a caller, so
     * the ownership that stops it committing another student's file is {@link #requireOpenBatch}
     * - which narrows on {@code user_id} and on the status the procedure would accept. A batch that is
     * not the caller's, or is already settled, is not found.
     *
     * <p><b>The rules are learned after the procedure, inside this transaction.</b> That ordering is
     * what makes the mappings correct: {@code resolved_category_id} is written by the procedure, not by
     * the preview, so reading the rows before the call would learn from the suggestion rather than from
     * the filing. Being in the same transaction means a mapping learned from an import that rolled back
     * is rolled back with it.
     *
     * <p>The response is the batch read back after the commit, so its counters are the procedure's own
     * and each row carries the transaction it became.
     */
    @Transactional
    public ImportBatchResponse commit(AuthenticatedUser principal, Long batchId) {
        Long userId = principal.userId();

        requireOpenBatch(userId, batchId);

        try {
            importProcedureDao.applyBatch(batchId);
        } catch (RuntimeException ex) {
            throw translateCommitFailure(ex, userId, batchId);
        }

        List<Category> visible = importCategoryResolver.visibleCategories(userId);
        List<ImportRow> rows = importViewDao.findRows(userId, batchId);
        int learned = importRuleLearner.learn(userId, rows, visible);

        log.info("CSV import committed userId={} batchId={} learnedRules={}", userId, batchId, learned);

        return importMapper.toBatchResponse(
                requireBatch(userId, batchId), importViewDao.findRows(userId, batchId));
    }

    /**
     * UC-11 A2: abandons the batch, so nothing is imported and nothing will be.
     *
     * <p>No procedure does this and none is needed: the transition is one column on a row the caller
     * owns, and {@code ImportWriteDao}'s statement carries both the ownership and the open-batch
     * predicate. The rows are deliberately left in place so the student can still see what they had -
     * and because {@code sp_apply_csv_batch} refuses a settled batch before it opens its cursor, a
     * cancelled batch's rows are never walked.
     *
     * <p>The response is the batch read back, so the client sees {@code CANCELLED} as the table stores
     * it and the counters the preview reached - the procedure never rewrote them, because it never ran.
     */
    @Transactional
    public ImportBatchResponse cancel(AuthenticatedUser principal, Long batchId) {
        Long userId = principal.userId();

        requireBatch(userId, batchId);

        if (importWriteDao.cancelBatch(userId, batchId) == 0) {
            throw new DataConflictException(
                    "This import is no longer open. Reload it to see its current state.");
        }

        log.info("CSV import cancelled userId={} batchId={}", userId, batchId);

        return readBatch(userId, batchId);
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /** One batch with its rows, for the responses that return the whole preview. */
    private ImportBatchResponse readBatch(Long userId, Long batchId) {
        return importMapper.toBatchResponse(
                requireBatch(userId, batchId), importViewDao.findRows(userId, batchId));
    }

    /**
     * One of the caller's batches, or {@code 404}.
     *
     * <p>A batch belonging to another student and a batch that does not exist produce the same answer,
     * deliberately - telling them apart would let a client enumerate other students' batch identifiers
     * (section 7.5).
     */
    private ImportBatchRow requireBatch(Long userId, Long batchId) {
        return importViewDao.findBatch(userId, batchId)
                .orElseThrow(() -> new NotFoundException("Import not found."));
    }

    /**
     * One of the caller's batches that can still be changed, or a refusal saying which it is not.
     *
     * <p>The two branches are asked in this order on purpose. Ownership first, so a batch that is not
     * the caller's is a {@code 404} and does not reveal whether it is open; then the state, which is a
     * fact about a batch the caller plainly owns and which they can act on by reloading. A single
     * answer for both would either tell a stranger whether a batch they do not own is still open, or
     * tell the student that their own batch does not exist.
     */
    private ImportBatchRow requireOpenBatch(Long userId, Long batchId) {
        ImportBatchRow batch = requireBatch(userId, batchId);

        if (!batch.status().isOpen()) {
            throw new DataConflictException(
                    "This import is no longer open. Reload it to see its current state.");
        }

        return batch;
    }

    /**
     * BR-07: a retired category cannot be chosen for a row that has not been imported yet.
     *
     * <p>The message names the field so the client can point at the picker. The database refuses the
     * same choice when the commit runs - {@code sp_apply_csv_batch} requires the chosen category to
     * still be active and turns the row into an error if it is not - so this is the version that says
     * why before the student commits a file rather than after.
     *
     * <p>The wording is {@code TransactionService}'s, because it is the same rule about the same kind
     * of choice and a student who has seen it once should recognise it.
     */
    private void requireActiveCategory(Category category) {
        if (Boolean.FALSE.equals(category.getIsActive())) {
            throw new RequestValidationException(
                    "The category is retired and cannot be used for new records.",
                    List.of(new ApiError.FieldError("categoryId",
                            "Choose a category that is still in use, or restore this one first.")));
        }
    }

    /**
     * Re-asks the duplicate question for every row it can be asked about, and writes each answer back.
     *
     * <p>Read once, decided once, written once - rather than a read and a write per row - so the rows
     * that the override did not affect are still re-examined against the row that changed. That is the
     * point: rows are compared in file order, so a corrected row can make a later row a duplicate or
     * stop it being one.
     *
     * <p><b>The batch's counters are recomputed at the end, and that is part of the same decision.</b>
     * A re-verdict can move a row between {@code VALID} and {@code DUPLICATE}, and the counters are what
     * the preview screen shows above the rows - so leaving them as the upload computed them would have
     * the batch say "nothing was already recorded" above a list of rows saying otherwise. The counts come
     * from the rows, so there is one definition of each counter and it is the one
     * {@code sp_apply_csv_batch} uses at the commit.
     */
    private void applyVerdicts(Long userId, Long batchId) {
        List<Category> visible = importCategoryResolver.visibleCategories(userId);
        List<Candidate> existing = importViewDao.findExistingRecords(userId);
        List<ImportRow> rows = importViewDao.findRows(userId, batchId);

        List<RowVerdict> verdicts = importPreviewer.verdicts(rows, visible, existing);
        for (RowVerdict verdict : verdicts) {
            importWriteDao.setRowVerdict(userId, batchId, verdict.rowId(), verdict.rowStatus(),
                    verdict.errorMessage());
        }

        importWriteDao.refreshBatchCounters(userId, batchId);
    }

    /**
     * The limit the caller asked for, or the default.
     *
     * <p>A value outside {@code 1}..{@link #MAX_LIST_LIMIT} is refused rather than clamped, because
     * the response echoes the limit it applied and a quietly reduced answer would make its own body
     * untrue - the decision {@code RecentActivityService#effectiveLimit} records for the same question.
     */
    private int effectiveLimit(int limit) {
        if (limit > 0 && limit <= MAX_LIST_LIMIT) {
            return limit;
        }

        throw new RequestValidationException(
                "The requested number of imports is out of range.",
                List.of(new ApiError.FieldError("limit",
                        "Ask for between 1 and " + MAX_LIST_LIMIT + " imports. Omit the parameter to "
                                + "get " + DEFAULT_LIST_LIMIT + ".")));
    }

    /**
     * Turns a refusal from the commit procedure into the API error that explains it.
     *
     * <p>Which rule fired is decided by {@link ImportWriteFailure}, which asks by SQLSTATE and by
     * constraint name rather than by matching the procedure's prose - the prose is not a contract and
     * is localised.
     *
     * <p>Both of the procedure's {@code SIGNAL}s collapse into one answer, and after the service's own
     * reads that is precisely right: the batch is not there to commit, or it stopped being open. The
     * caller's response to either is the same - reload and look at the status - so distinguishing them
     * would be producing a distinction the client cannot use.
     *
     * <p>The exception is deliberately not logged: its message carries the procedure's text and the
     * offending id. The user id and batch id describe the refusal well enough to investigate it.
     */
    private RuntimeException translateCommitFailure(RuntimeException ex, Long userId, Long batchId) {
        if (ImportWriteFailure.isSignalledRefusal(ex)) {
            log.info("CSV import commit refused userId={} batchId={}", userId, batchId);
            return new DataConflictException(
                    "This import is no longer open. Reload it to see its current state.");
        }

        if (ImportWriteFailure.isConstraintViolation(ex)) {
            log.warn("CSV import commit rejected by a constraint userId={} batchId={}", userId, batchId);
            return new DataConflictException(
                    "This import is no longer open. Reload it to see its current state.");
        }

        log.error("CSV import commit failed unexpectedly userId={} batchId={}", userId, batchId, ex);
        return ex;
    }
}
