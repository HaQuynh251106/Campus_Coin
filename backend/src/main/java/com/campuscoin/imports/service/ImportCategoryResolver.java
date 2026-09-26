package com.campuscoin.imports.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.campuscoin.categorisation.entity.CategoryAdvice;
import com.campuscoin.categorisation.entity.SuggestionCandidate;
import com.campuscoin.categorisation.service.CategorySuggester;
import com.campuscoin.category.entity.Category;
import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.category.repository.CategoryRepository;

/**
 * Answers the two category questions the preview asks about one row (UC-11).
 *
 * <p>They are different questions and the difference is the whole reason this class exists.
 *
 * <ul>
 *   <li><b>Which category does this row's own name refer to?</b> That is what the file said, resolved
 *       the way {@code sp_apply_csv_batch}'s Case B resolves it: by type and name, preferring the
 *       student's own category over the shared default. It is used only to compare the row against the
 *       student's existing records - see {@code ImportDuplicateDetector} - and it is deliberately never
 *       written to {@code resolved_category_id}, which the procedure treats as the student's own
 *       authoritative choice.</li>
 *   <li><b>What category would the system propose for this row?</b> That is UC-08 applied during an
 *       import, and its answer <em>is</em> written to {@code ai_suggested_category_id} - stored beside
 *       the row as a note, never as the row's category, which is BR-13's rule and
 *       {@code CategorySuggester}'s contract.</li>
 * </ul>
 *
 * <p><b>This class is where OB-006 is closed.</b> That blocker records that
 * {@code import_rows.ai_suggested_category_id} has no SQL-level ownership guard - "the column may only
 * point at a default category or at one owned by the student importing the file", as the schema's own
 * comment says, but nothing in the database enforces it. It is a schema-level gap this module does not
 * fix, because adding a trigger is a schema change and the blocker's recommended resolution is the
 * application one. So the guarantee is made here instead, structurally:
 * {@link #suggestedCategoryId} can only return an id drawn from {@link #visibleCategories}, which is
 * {@code CategoryRepository#findVisibleToUser} - the query whose whole predicate is "this student's own
 * rows, or the shared defaults". There is no other code path to the column, and no id from a request or
 * a file ever reaches it.
 *
 * <p><b>The visible set is read once per file, not once per row.</b> A preview of two hundred rows would
 * otherwise issue two hundred identical category queries; the set is the same for every row, so the
 * service reads it once and passes it in. It is also the reason this class takes a {@code userId} rather
 * than reading from a principal: it narrows an already-read list, and the list is what carries the
 * caller's identity.
 *
 * <p>Stateless and free of a transaction of its own, so the resolution and the proposal can both be
 * unit-tested with plain lists - the shape {@code CategorySuggester} and {@code AnomalyDetector} share.
 */
@Component
public class ImportCategoryResolver {

    private final CategoryRepository categoryRepository;
    private final CategorySuggester categorySuggester;

    public ImportCategoryResolver(CategoryRepository categoryRepository,
                                  CategorySuggester categorySuggester) {
        this.categoryRepository = categoryRepository;
        this.categorySuggester = categorySuggester;
    }

    /**
     * The categories this student may file under, active ones only.
     *
     * <p>Retired categories are filtered out here, once, for the reason
     * {@code CategorisationService#activeCandidates} records: {@code findVisibleToUser} includes them so
     * module 3's endpoint can offer a restore, but a category the student may not file new records under
     * must not be proposed for one, nor used as the comparison target that decides whether a row is a
     * duplicate.
     */
    public List<Category> visibleCategories(Long userId) {
        return categoryRepository.findVisibleToUser(userId).stream()
                .filter(category -> !Boolean.FALSE.equals(category.getIsActive()))
                .toList();
    }

    /**
     * UC-11 B6: the category the row's own name refers to, resolved as the commit procedure resolves it.
     *
     * <p>Matched by name and type together, because BR-05 makes the type the record's type and a name
     * alone does not determine one - {@code Miscellaneous} exists once but a student's own category could
     * share a name with an income default. The personal row wins over the shared one, which is
     * {@code ORDER BY (user_id IS NULL)} in the procedure and {@code Comparator} here: the same rule, and
     * {@code SuggestionCandidate#isDefault} records that the two are deliberately the same.
     *
     * <p>An empty answer is not a failure and is not reported as one. A name that matches nothing simply
     * falls back to the default category <em>at the commit</em> - the procedure does that, not this
     * method - and a row with no resolved id is never a duplicate.
     */
    public Optional<Long> resolveByName(List<Category> visible, String categoryName,
                                        CategoryType type) {
        if (categoryName == null || categoryName.isBlank() || type == null) {
            return Optional.empty();
        }
        String name = categoryName.trim();

        return visible.stream()
                .filter(category -> category.getType() == type)
                .filter(category -> name.equalsIgnoreCase(category.getName()))
                .min(Comparator.comparing(Category::isDefault))
                .map(Category::getId);
    }

    /**
     * UC-11 B6: the category the student chose to correct a row, if they may use it.
     *
     * <p>Answers the two questions an override asks - does this category exist for this student, and is
     * it retired - in one read, and returns the row rather than a boolean so the caller can raise its
     * own field error naming the field the client sent. It is here rather than in the service because
     * this class is the module's one door to {@code categories}: the visible set, the by-name
     * resolution and the suggestion all come through it, and a service that also reached into
     * {@code CategoryRepository} directly would be a second place where "which categories may this
     * student use" is decided.
     *
     * <p>Deliberately <em>not</em> narrowed to active categories, which is the difference from
     * {@link #visibleCategories}: a retired category is found so the caller can refuse it by name with
     * a message about retiring rather than answering a bare "no such category" for one the student can
     * plainly see in their own list. The same split, and the same reason, as
     * {@code TransactionService}'s two guards.
     *
     * <p>{@code findVisibleById} is the query that expresses the ownership half - the student's own
     * rows and the shared defaults, by id - so a category belonging to another student is simply not
     * found.
     */
    public Optional<Category> findVisible(Long userId, Long categoryId) {
        if (categoryId == null) {
            return Optional.empty();
        }
        return categoryRepository.findVisibleById(categoryId, userId);
    }

    /**
     * UC-08 during an import: the category the system would propose for this row.
     *
     * <p>The proposal goes through the same {@code CategorySuggester} the categorisation endpoint uses -
     * the student's own learned rules first, then the provider, then nothing - so an import teaches and
     * is taught by the same rules as a hand-filed record. A proposal the suggester cannot resolve to one
     * of the student's own categories is discarded there, so nothing that arrives here can name a
     * category they may not use.
     *
     * <p>An empty answer is the ordinary case: most imported rows have no learned rule and no provider
     * is configured, so {@code ai_suggested_category_id} stays null. That is a real answer - "nothing was
     * proposed" - and the preview says so by leaving the column empty rather than by inventing a guess.
     *
     * @return a category id drawn from {@code visible}, or null - the OB-006 guarantee, stated as the
     *         method's own return contract
     */
    public Long suggestedCategoryId(List<Category> visible, List<com.campuscoin.categorisation.entity.CategoryRuleRow> rules,
                                    String description) {
        if (description == null || description.isBlank()) {
            return null;
        }

        CategoryAdvice advice = categorySuggester.advise(description, rules, toCandidates(visible));
        Long proposed = advice.categoryId();
        if (proposed == null) {
            return null;
        }

        // OB-006: the last line of defence, and it is deliberately after the suggester rather than
        // instead of trusting it. The suggester already resolves against this same list, so this can only
        // ever reject something a future change to that class let through - which is exactly the case a
        // schema-level trigger would have caught, and the case the blocker says is unguarded.
        return visible.stream()
                .anyMatch(category -> proposed.equals(category.getId()))
                ? proposed
                : null;
    }

    private static List<SuggestionCandidate> toCandidates(List<Category> visible) {
        return visible.stream()
                .map(category -> new SuggestionCandidate(
                        category.getId(), category.getName(), category.getType(), category.isDefault()))
                .toList();
    }
}
