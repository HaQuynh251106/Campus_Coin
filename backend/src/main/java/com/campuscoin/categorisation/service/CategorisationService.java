package com.campuscoin.categorisation.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.categorisation.dto.CategorySuggestionResponse;
import com.campuscoin.categorisation.dto.LearnedCategoryRuleResponse;
import com.campuscoin.categorisation.entity.CategoryAdvice;
import com.campuscoin.categorisation.entity.CategoryRuleRow;
import com.campuscoin.categorisation.entity.RuleSource;
import com.campuscoin.categorisation.entity.SuggestionCandidate;
import com.campuscoin.categorisation.entity.TransactionCategorisationRow;
import com.campuscoin.categorisation.mapper.CategorisationMapper;
import com.campuscoin.categorisation.repository.CategoryRuleDao;
import com.campuscoin.categorisation.repository.TransactionCategorisationDao;
import com.campuscoin.category.entity.Category;
import com.campuscoin.category.repository.CategoryRepository;
import com.campuscoin.common.exception.DataConflictException;
import com.campuscoin.common.exception.NotFoundException;

/**
 * Categorises one of the student's own records and learns from where they filed it (UC-08).
 *
 * <p><b>One endpoint, and the whole of UC-08 is behind it.</b> The student files a record through module
 * 4 as they always do; calling this afterwards asks the system what it would have proposed, records that
 * proposal beside the record, and teaches it the mapping their filing implies. Nothing here changes where
 * the money is counted - see below.
 *
 * <p><b>The record's own category is never touched.</b> BR-13 makes every result advisory, and the place
 * that is enforced is here: the service writes {@code ai_suggested_category_id}, {@code ai_confidence}
 * and {@code ai_overridden} on the record and never {@code category_id}. So the student's filing decides
 * what the record means, this module only ever adds a note beside it, and a fault in this class cannot
 * move a student's spending from Food to Transport. That is what makes the writes safe to make at all,
 * and it is why the one write that would be dangerous is absent rather than guarded.
 *
 * <p><b>Ownership is the read's, not a check's.</b> The record is loaded by {@code (id, user_id,
 * is_deleted = 0)}, so another student's record and a record that does not exist produce the same empty
 * answer and the same {@code 404}. There is nothing to distinguish them with, which is the point: a
 * response that told them apart would let a caller enumerate record identifiers
 * (section 7.5) - the policy {@code TransactionService} and {@code RecentActivityService} both record
 * for the same table.
 *
 * <p><b>Every write is conditional on the state the read established.</b> The suggestion is written only
 * when it differs from what is already stored, because {@code trg_transactions_after_update} appends a
 * {@code transaction_history} row when one of the three columns moves - and a request that would move
 * nothing must not put noise into the log BR-09 keeps. The keyword mapping is written on every call that
 * had a description to learn from, because {@code category_rules} has no history trigger and no
 * maintained counters, so the write is idempotent by construction and repeating it is what keeps the
 * mapping's {@code source} describing the latest filing.
 *
 * <p><b>Learning is a by-product, and its failure is not the student's problem.</b> The suggestion is
 * what the request is for; the mapping is what the system gets out of it. So the two are written in one
 * transaction - a mapping learned from a filing that was not recorded would be the system remembering
 * something that did not happen - but a refusal on the record's own update is translated rather than
 * surfaced as a database message (see {@code CategorisationWriteFailure}).
 *
 * <p><b>No category id is accepted from the client.</b> The only id in the request is the record's, and
 * the category the mapping is taught comes from the record itself. That is what makes the learning a
 * fact about the student's data rather than a claim they typed, and it is why the request carries no
 * category field to validate.
 */
@Service
public class CategorisationService {

    private static final Logger log = LoggerFactory.getLogger(CategorisationService.class);

    private final TransactionCategorisationDao transactionCategorisationDao;
    private final CategoryRuleDao categoryRuleDao;
    private final CategoryRepository categoryRepository;
    private final CategorySuggester categorySuggester;
    private final CategorisationMapper categorisationMapper;

    public CategorisationService(TransactionCategorisationDao transactionCategorisationDao,
                                 CategoryRuleDao categoryRuleDao,
                                 CategoryRepository categoryRepository,
                                 CategorySuggester categorySuggester,
                                 CategorisationMapper categorisationMapper) {
        this.transactionCategorisationDao = transactionCategorisationDao;
        this.categoryRuleDao = categoryRuleDao;
        this.categoryRepository = categoryRepository;
        this.categorySuggester = categorySuggester;
        this.categorisationMapper = categorisationMapper;
    }

    /**
     * UC-08: proposes a category for one of the caller's records and records what the filing taught.
     *
     * <p>A write endpoint, not a read, and the write is the reason it is a {@code POST} rather than a
     * {@code GET}: it leaves the proposal on the record and leaves a keyword mapping behind. The same
     * call twice over an unchanged record writes nothing the second time - the suggestion is already
     * there and the mapping already points where it points - so it is idempotent in the sense that
     * matters: repeating it cannot accumulate rows.
     */
    @Transactional
    public CategorySuggestionResponse suggest(Long userId, Long transactionId) {
        TransactionCategorisationRow row = transactionCategorisationDao.find(userId, transactionId)
                .orElseThrow(() -> new NotFoundException("Transaction not found."));

        String description = categorisationMapper.description(row);
        List<Category> visible = categoryRepository.findVisibleToUser(userId);
        CategoryAdvice advice = categorySuggester.advise(
                description, categoryRuleDao.findRules(userId), activeCandidates(visible));
        boolean overridden = isOverride(row, advice);

        writeSuggestion(userId, transactionId, row, advice, overridden);
        LearnedCategoryRuleResponse learned = recordRule(
                userId, row, description, overridden, categoryNameOf(visible, row.categoryId()));

        log.info("Categorisation suggested userId={} transactionId={} source={} overridden={} learned={}",
                userId, transactionId, advice.source(), overridden, learned != null);

        return new CategorySuggestionResponse(
                transactionId,
                advice.source(),
                advice.categoryId(),
                advice.categoryName(),
                advice.type(),
                advice.confidence(),
                advice.reason(),
                learned);
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /**
     * BR-13: whether the filing contradicts the proposal.
     *
     * <p>Decided by comparing the proposed category with the category the record is actually in, and
     * never by the confidence the proposal carried. A suggestion that is absent - {@code NONE} - is not
     * an override: nothing was proposed, so nothing was corrected, and the student is simply filing the
     * record the way they always would have.
     *
     * <p>The comparison is on the id rather than on the name, because the id is what the response
     * resolved the proposal to and what the record's own foreign key holds. Two categories can share a
     * name across scopes; only the id says which row the record points at.
     */
    private static boolean isOverride(TransactionCategorisationRow row, CategoryAdvice advice) {
        Long suggested = advice.categoryId();
        return suggested != null && !suggested.equals(row.categoryId());
    }

    /**
     * The categories this student may file under, active ones only.
     *
     * <p>{@code findVisibleToUser} returns the student's own rows and the shared defaults, and includes
     * retired ones - the endpoint module 3 serves has to show a retired category so the student can
     * restore it. A suggestion is a different question: filing new records is what
     * {@code requireActiveCategory} refuses for a retired row (BR-07), and proposing one would be
     * offering the student an action the database would then reject. So the filter is applied here, once,
     * and both the suggester's candidate list and the rule-match check see the same active set.
     *
     * <p>Filtered in Java rather than in the query because {@code findVisibleToUser} is module 3's read,
     * shared with the category endpoints, and narrowing it here would either change what that endpoint
     * returns or add a second query answering the same question with one more predicate. The list is a
     * student's own categories - tens of rows - so there is nothing to gain from making the database do
     * it.
     *
     * <p>Takes the list rather than reading it, because the same read also names the record's own
     * category for the response - and reading it twice would let the two answers disagree.
     */
    private static List<SuggestionCandidate> activeCandidates(List<Category> visible) {
        return visible.stream()
                .filter(category -> !Boolean.FALSE.equals(category.getIsActive()))
                .map(CategorisationService::toCandidate)
                .toList();
    }

    /**
     * The name of the category the record is filed under.
     *
     * <p>Needed because the response describes the mapping this call stored, and that mapping points at
     * the student's own category - which is not necessarily the category that was <em>proposed</em>, and
     * is nothing at all when nothing was proposed. Reading the name from the proposal would therefore
     * leave the very common "no suggestion, mapping stored anyway" case reporting an id with no name,
     * and would report the wrong name whenever the filing overrode a suggestion.
     *
     * <p>The row is guaranteed to be in the list: {@code findVisibleToUser} returns the caller's own rows
     * and the shared defaults, and a record's category is necessarily one of the two -
     * {@code fk_txn_category} makes it a real row and BR-06 makes a category either the student's or
     * nobody's. A miss would mean the read and the record disagreed, which is why it is not answered with
     * a null name.
     */
    private static String categoryNameOf(List<Category> visible, Long categoryId) {
        return visible.stream()
                .filter(category -> categoryId.equals(category.getId()))
                .map(Category::getName)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Category " + categoryId + " is not visible to the owner of the record filed "
                                + "under it."));
    }

    private static SuggestionCandidate toCandidate(Category category) {
        return new SuggestionCandidate(
                category.getId(), category.getName(), category.getType(), category.isDefault());
    }

    /**
     * Writes the proposal beside the record, unless it is already there.
     *
     * <p>The skip is not an optimisation. {@code trg_transactions_after_update} appends a
     * {@code transaction_history} row whenever {@code ai_suggested_category_id}, {@code ai_confidence}
     * or {@code ai_overridden} actually changes, so writing the same triple again would add a row to the
     * log BR-09 keeps for a request that changed nothing. Asking for the same suggestion twice is
     * therefore a read on the second call, and the history stays an account of what happened rather than
     * of how often a screen was opened - the reasoning {@code AnomalyService}'s unchanged check
     * records for the same trigger.
     *
     * <p>The affected-row count is checked rather than assumed. A write that matched nothing means the
     * record was moved or trashed between the read and the write - not that the write was unnecessary,
     * because the unnecessary case never reaches the statement. Reporting success for it would leave the
     * client with a response describing a state the table does not hold.
     */
    private void writeSuggestion(Long userId, Long transactionId, TransactionCategorisationRow row,
                                 CategoryAdvice advice, boolean overridden) {
        if (!differsFromStored(row, advice, overridden)) {
            return;
        }

        int updated;
        try {
            updated = transactionCategorisationDao.updateSuggestion(
                    userId, transactionId, advice.categoryId(), advice.confidence(), overridden);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, userId, transactionId);
        }

        if (updated != 1) {
            throw new IllegalStateException("Categorisation wrote " + updated
                    + " rows for transaction " + transactionId + ", which should be exactly one.");
        }
    }

    /**
     * Whether the proposed triple differs from the one stored.
     *
     * <p>The confidence is compared numerically rather than with {@code equals}, because the column is
     * {@code DECIMAL(5,4)} and reads back at scale four while a provider's answer arrives as a double
     * with whatever scale {@code BigDecimal.valueOf} gives it - so {@code 1.0000} stored and
     * {@code 1.0} proposed are the same value and an equality test would call them different, writing a
     * history row every time for a suggestion that never moved.
     */
    private static boolean differsFromStored(TransactionCategorisationRow row, CategoryAdvice advice,
                                             boolean overridden) {
        return !Objects.equals(row.aiSuggestedCategoryId(), advice.categoryId())
                || !sameValue(row.aiConfidence(), advice.confidence())
                || row.aiOverridden() != overridden;
    }

    private static boolean sameValue(BigDecimal stored, BigDecimal proposed) {
        if (stored == null || proposed == null) {
            return stored == null && proposed == null;
        }
        return stored.compareTo(proposed) == 0;
    }

    /**
     * UC-08 B6: teaches the system the mapping this filing implies.
     *
     * <p>The keyword is the student's whole normalised description, and the category is the one the
     * record is in - both attested by the row, neither supplied by the client. The source is
     * {@code OVERRIDE} when the filing contradicted a proposal and {@code ACCEPTED} otherwise, which
     * includes the first filing of a description, where there was no proposal to contradict; without
     * that case the feature could never start learning. {@code RuleSource} records why that reading is
     * the only one that works.
     *
     * <p>Every field of the answer comes from the record rather than from the proposal - the category's
     * name included. The mapping points where the student put the record, so reporting the proposed
     * category's name would name a category the mapping does not point at, and would have nothing at all
     * to name on the many calls where nothing was proposed and the mapping was still stored.
     *
     * <p>Omitted rather than reported-as-nothing when there is no keyword to learn: a record with no
     * description, or one whose description is longer than {@code category_rules.keyword} can hold, still
     * gets its suggestion - it simply does not change what the system remembers, and saying "we learned
     * nothing" for every such record would be a field that is always null.
     */
    private LearnedCategoryRuleResponse recordRule(Long userId, TransactionCategorisationRow row,
                                                   String description, boolean overridden,
                                                   String categoryName) {
        String keyword = CategoryRuleMatcher.learnableKeyword(description);
        if (keyword == null) {
            return null;
        }

        RuleSource source = overridden ? RuleSource.OVERRIDE : RuleSource.ACCEPTED;
        try {
            categoryRuleDao.upsert(userId, keyword, row.categoryType(), row.categoryId(), source);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, userId, null);
        }

        return new LearnedCategoryRuleResponse(keyword, row.categoryId(), categoryName, source);
    }

    /**
     * Turns a refused write into the answer the caller should get.
     *
     * <p>Both writable statements run inside one transaction, so a refusal here is a refusal of the whole
     * request and the message has to describe that rather than name the statement that happened to fire.
     *
     * <p>A {@code SIGNAL} - BR-13, BR-08 - means the database would not accept the advisory note on this
     * record, and the answer is the record's own: "not found". Reporting the rule would tell the caller
     * something about a record they may not be entitled to know about, and neither rule is a fault in
     * their request - they filed the record through module 4 and its date and category were validated
     * then.
     *
     * <p>A constraint violation means a foreign key or a check refused the write, which the suggester and
     * the caller cannot cause - every category is resolved against the student's own list first and the
     * confidence is bounded before it is written. So it is answered as a conflict the caller can retry
     * rather than as a server fault, and the database's own text is not forwarded: MySQL's message names
     * the constraint and the offending value, and neither is part of the contract.
     *
     * <p>The exception is deliberately not logged whole. Its message carries the offending identifier;
     * the ids in the log line describe the refusal well enough to investigate it.
     */
    private RuntimeException translateWriteFailure(RuntimeException ex, Long userId, Long transactionId) {
        if (CategorisationWriteFailure.isSignalledRefusal(ex)) {
            log.info("Categorisation refused by the database userId={} transactionId={}",
                    userId, transactionId);
            return new NotFoundException("Transaction not found.");
        }

        if (CategorisationWriteFailure.isConstraintViolation(ex)) {
            log.info("Categorisation rejected by a constraint userId={} transactionId={}",
                    userId, transactionId);
            return new DataConflictException(
                    "The suggestion could not be recorded for this transaction. Reload and try again.");
        }

        log.error("Categorisation write failed unexpectedly userId={} transactionId={}",
                userId, transactionId, ex);
        return ex;
    }
}
