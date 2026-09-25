package com.campuscoin.category.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.campuscoin.category.entity.Category;
import com.campuscoin.category.entity.CategoryType;

/**
 * MySQL access for {@code categories} (UC-06).
 *
 * <p>Every query here that looks up a single row takes the owning student's id as well as the
 * category id. That is deliberate and is the whole of the ownership guarantee: there is no method
 * that can fetch a category by id alone, so a service method cannot accidentally act on another
 * student's row - including the seeded default rows, which belong to nobody. BR-02 requires
 * ownership to be enforced server-side, and making it a property of the queries rather than a
 * check someone has to remember is the version of that which cannot be forgotten.
 *
 * <p>What is <em>not</em> here: any query about {@code transactions}, {@code budgets} or
 * {@code recurring_rules}. Those tables belong to later modules, and asking about them from this
 * one would mean this package depending on entities it has no business knowing about. The rule
 * that concerns them - a category referenced by any of the three may not change its type, and one
 * with a budget may not be deleted - is enforced by {@code trg_categories_before_update} and
 * {@code trg_categories_before_delete}, which is where it belongs, because it holds for hand-run
 * statements too. {@link com.campuscoin.category.service.CategoryService} translates those
 * refusals into API errors rather than restating the rules.
 *
 * <p>The uniqueness checks are here because the database is the authority and this layer can only
 * ask. {@code uk_categories_scope_type_name} is what actually prevents a duplicate; the service
 * consults these first so a client receives a field-level error naming the reason instead of a
 * generic conflict.
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /**
     * Every category this student can choose from: their own, plus the shared defaults.
     *
     * <p>Both kinds are returned in one list because that is how they are used - a picker offers
     * one set of choices, and the client tells them apart by {@code isDefault} rather than by
     * calling twice. Two endpoints would only move the merge into the frontend.
     *
     * <p>Ordered by type, then {@code sort_order}, then {@code user_id}, then id:
     *
     * <ul>
     *   <li>{@code sort_order} is the column the schema provides for ordering, and the index
     *       {@code ix_categories_user_type_active} ends in it.</li>
     *   <li>{@code user_id} is the tie-break that puts shared defaults first wherever a default and
     *       a personal category share a sort order. MySQL sorts NULL before non-NULL ascending, and
     *       a default category is the one whose {@code user_id} is NULL - so a student's own
     *       categories appear after the standard ones they duplicate, which is the helpful order.</li>
     *   <li>id makes the order total. {@code sort_order} is not unique, and without a final
     *       tie-break MySQL may return equal rows in either order, so the list would appear to
     *       shuffle between two identical calls.</li>
     * </ul>
     *
     * <p>Retired rows are included. UC-06 lets a student keep a category they have stopped using,
     * and hiding it would leave no way to find and restore it; the response carries
     * {@code isActive} so the client decides how to show it.
     */
    @Query("""
            SELECT c FROM Category c
             WHERE c.userId = :userId OR c.userId IS NULL
             ORDER BY c.type ASC, c.sortOrder ASC, c.userId ASC, c.id ASC
            """)
    List<Category> findVisibleToUser(@Param("userId") Long userId);

    /**
     * One of the student's own categories, identified by both ids.
     *
     * <p>Returns empty both when the category does not exist and when it belongs to someone else -
     * or to nobody, which is what a default category does. The caller cannot tell those apart,
     * which is the point: a response that distinguished them would let a client probe for the
     * existence of other students' categories (section 7.5).
     */
    Optional<Category> findByIdAndUserId(Long id, Long userId);

    /**
     * One category this student may file records under: their own, or a shared default.
     *
     * <p>This is the question a transaction asks - "may I use this category?" - and it is a
     * different question from {@link #findByIdAndUserId}, which asks "is this mine to edit?".
     * Filing under a shared default is the normal case for the seeded categories, so a lookup that
     * excluded them would refuse the commonest request, and a lookup that returned any row at all
     * would let a student file records under somebody else's personal category (BR-02).
     *
     * <p>Kept here rather than in the module that needs it, because it is a statement about
     * {@code categories} and the ownership rule is the whole point of this interface. The name
     * echoes {@link #findVisibleToUser} so the two cannot be mistaken for each other: this one
     * selects from the same set, by id.
     *
     * <p>Returns empty for an id that does not exist and for one belonging to another student alike,
     * so a caller cannot use it to probe which ids exist (section 7.5).
     */
    @Query("""
            SELECT c FROM Category c
             WHERE c.id = :id AND (c.userId = :userId OR c.userId IS NULL)
            """)
    Optional<Category> findVisibleById(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Whether this student already has a personal category with this name and type, other than the
     * one being changed.
     *
     * <p>{@code excludedId} is passed as -1 on the create path, where there is no row to exclude -
     * an id can never be negative, because the column is {@code BIGINT UNSIGNED}. One method serves
     * both paths so that the uniqueness rule has a single home; a second method without the
     * exclusion would be a second place for it to drift.
     */
    boolean existsByUserIdAndTypeAndNameAndIdNot(Long userId, CategoryType type, String name,
                                                 Long excludedId);

    /**
     * Whether a shared default category already uses this name and type.
     *
     * <p>UC-06 does not permit a personal category to shadow a default one, so that it is never
     * ambiguous which of two identically named categories a picker means.
     *
     * <p>{@code trg_categories_before_insert} enforces this on insert. It does <em>not</em> enforce
     * it on update - the update trigger checks only scope and type changes - so a rename could
     * otherwise reach the very state the rule exists to prevent. The service applies this same
     * check on both paths, which makes the API the stricter of the two and keeps create and rename
     * consistent. That is a deliberate application-side decision, recorded in the module report: it
     * needs no schema change and it closes a gap rather than inventing a rule, since the rationale
     * is the one already written in the trigger.
     */
    boolean existsByUserIdIsNullAndTypeAndName(CategoryType type, String name);
}
