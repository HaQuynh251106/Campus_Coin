package com.campuscoin.imports.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.campuscoin.categorisation.entity.RuleSource;
import com.campuscoin.categorisation.repository.CategoryRuleDao;
import com.campuscoin.categorisation.service.CategoryRuleMatcher;
import com.campuscoin.category.entity.Category;
import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.imports.entity.ImportRow;
import com.campuscoin.imports.entity.ImportRowStatus;

/**
 * Teaches the system the mappings an import established (UC-11, UC-08 B6).
 *
 * <p><b>This is the writer of {@code RuleSource.IMPORT}, and it is the second caller that made
 * {@code CategoryRuleMatcher#learnableKeyword} public.</b> UC-08's learning loop is fed from a filing -
 * the student files a record and module 4's categorisation endpoint remembers what they chose. An
 * import is a file full of filings that arrive all at once, and if it taught nothing the feature would
 * have a hole exactly where the student has the most data: a student who imports a year of statements
 * would get no suggestions for any of the merchants in them, and would have to re-file one by hand
 * before the system knew the name.
 *
 * <p><b>What it learns from, stated exactly, because this decides what the table fills up with.</b> One
 * mapping per row that {@code sp_apply_csv_batch} actually imported, from that row's own
 * {@code parsed_description} to the category the row's transaction was filed under - which the
 * procedure has just written to {@code resolved_category_id}, whether it came from the student's
 * override or from the file's own name. So the loop closes on the same fact the student's records now
 * hold, and it needs no second opinion about what the row's category "should have been":
 *
 * <ul>
 *   <li><b>Nothing is learned for a row that was not imported.</b> An error row and a duplicate row
 *       produced no record, so there is no filing to learn from - and a mapping invented for a row the
 *       student never ended up owning would be the system claiming knowledge it was not given.</li>
 *   <li><b>Nothing is learned from a row with no description, or one too long for the keyword
 *       column.</b> {@link CategoryRuleMatcher#learnableKeyword} answers {@code null} for both, and
 *       that is not a failure: the row was imported correctly, it just taught nothing - the same
 *       ordinary outcome {@code CategorisationService} records for a hand filing with no description.</li>
 *   <li><b>Nothing is learned when the row's category is not one of the caller's own visible
 *       categories.</b> The type has to travel with the category because BR-05 makes the category's
 *       type the record's type, and the visible list is where that type is read from. A miss here would
 *       mean the procedure filed a record under a category the student cannot see, which it cannot do -
 *       it requires the category to be the student's own or a shared default, and active. The row is
 *       skipped rather than failing the commit, because the import is the point and this is a
 *       by-product (see below).</li>
 * </ul>
 *
 * <p><b>Learning runs inside the commit's transaction and after the procedure has run.</b> Reading the
 * rows afterwards is what makes {@code resolved_category_id} available at all - it is written by the
 * procedure, not by the preview - so learning from the preview's own guess would be learning from a
 * question rather than from the answer. Being in the same transaction is deliberate for the same
 * reason {@code CategorisationService} writes its suggestion and its mapping together: a mapping
 * learned from an import that then rolled back would be the system remembering an import that did not
 * happen.
 *
 * <p><b>The upsert is idempotent, so re-committing cannot accumulate rows.</b> {@code uk_rule_user_keyword}
 * makes one mapping per student, keyword and mode, so a file imported twice teaches the same mappings
 * twice and the second pass only rewrites {@code source}. It is not an error and it is not prevented:
 * the import itself is the thing that must not double, and that is
 * {@code ImportDuplicateDetector}'s job at the preview.
 *
 * <p>No transaction annotation of its own. Its single collaborator's write joins whatever transaction
 * the caller has, and this class reads nothing - the caller has already read the rows.
 */
@Component
public class ImportRuleLearner {

    private final CategoryRuleDao categoryRuleDao;

    public ImportRuleLearner(CategoryRuleDao categoryRuleDao) {
        this.categoryRuleDao = categoryRuleDao;
    }

    /**
     * UC-11: teaches a mapping for every row the commit imported.
     *
     * @param userId  the caller, whose own rule table this is - never a value from a request
     * @param rows    the batch's rows as they read <em>after</em> {@code sp_apply_csv_batch} ran, so
     *                {@code rowStatus} is {@code IMPORTED} and {@code resolvedCategoryId} is filled for
     *                every row that became a transaction
     * @param visible the caller's own active categories, as {@code ImportCategoryResolver} read them
     * @return how many mappings were taught, for the caller's log line - never a value the API reports,
     *         because the number of rules a student has is not a fact about their import
     */
    public int learn(Long userId, List<ImportRow> rows, List<Category> visible) {
        Map<Long, CategoryType> typeByCategoryId = typesById(visible);
        int learned = 0;

        for (ImportRow row : rows) {
            if (row.rowStatus() != ImportRowStatus.IMPORTED) {
                continue;
            }

            String keyword = CategoryRuleMatcher.learnableKeyword(row.parsedDescription());
            if (keyword == null) {
                continue;
            }

            CategoryType type = typeByCategoryId.get(row.resolvedCategoryId());
            if (type == null) {
                continue;
            }

            categoryRuleDao.upsert(userId, keyword, type, row.resolvedCategoryId(), RuleSource.IMPORT);
            learned++;
        }

        return learned;
    }

    /**
     * The type of each visible category, keyed by id.
     *
     * <p>Built once for the whole batch rather than searched per row: a file with several hundred rows
     * would otherwise scan the category list several hundred times to answer the same handful of
     * questions, and the list does not change while the commit runs.
     *
     * <p>A category id that appears twice cannot: {@code categories.id} is the primary key, so the merge
     * function below is unreachable and exists only because the collector requires one.
     */
    private static Map<Long, CategoryType> typesById(List<Category> visible) {
        Map<Long, CategoryType> types = new HashMap<>();
        for (Category category : visible) {
            types.putIfAbsent(category.getId(), category.getType());
        }
        return types;
    }
}
