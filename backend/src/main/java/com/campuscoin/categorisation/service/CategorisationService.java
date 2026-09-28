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

    private static boolean isOverride(TransactionCategorisationRow row, CategoryAdvice advice) {
        Long suggested = advice.categoryId();
        return suggested != null && !suggested.equals(row.categoryId());
    }

    private static List<SuggestionCandidate> activeCandidates(List<Category> visible) {
        return visible.stream()
                .filter(category -> !Boolean.FALSE.equals(category.getIsActive()))
                .map(CategorisationService::toCandidate)
                .toList();
    }

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
