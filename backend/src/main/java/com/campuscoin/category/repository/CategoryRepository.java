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

    /**
     * Every shared default category, for the administration screen (UC-20, module 11).
     *
     * <p>This is the one read in this interface written for an administrator rather than a student,
     * and it lives here rather than in the administration package because it is a statement about
     * {@code categories} and nothing else - the same reason {@link #findVisibleById} lives here. A
     * second interface over the same table would be a second definition of the table's shape, and
     * module 11 has no other reason to read {@code categories} in bulk.
     *
     * <p><b>Why UC-20 needs its own read.</b> {@link #findVisibleToUser} answers "what may this
     * student choose from", which merges the shared rows with the caller's own. An administrator
     * editing the shared ones needs exactly the opposite: only {@code user_id IS NULL}, with no
     * caller's rows mixed in. Expressing that as a filter over the student read would be a
     * duplicate capability with a different answer.
     *
     * <p>Retired rows are included, as in {@link #findVisibleToUser}: {@code is_active = false} is
     * how BR-07 retires a category instead of deleting it, and a retired default is precisely one an
     * administrator needs to find in order to bring back. The response carries {@code isActive}, so
     * hiding them would make the flag unreachable.
     *
     * <p>Ordered by type, then {@code sort_order}, then id. The first two are the columns the
     * student-facing list orders by, so both screens present the shared rows in the same sequence;
     * id makes the order total, since {@code sort_order} is not unique and two equal rows could
     * otherwise swap between two identical calls.
     */
    @Query("""
            SELECT c FROM Category c
             WHERE c.userId IS NULL
             ORDER BY c.type ASC, c.sortOrder ASC, c.id ASC
            """)
    List<Category> findAllDefaults();

    /**
     * One shared default category, or empty when the id belongs to a student's own category or to
     * nothing.
     *
     * <p>Used by UC-20's update path to read the row <em>back</em> after the write, so the response
     * describes what the table holds rather than what the request asked for. That ordering matters:
     * {@code sp_admin_upsert_default_category} writes through native SQL and Hibernate does not see
     * it, so a category loaded from here <em>before</em> the call would be handed back unchanged
     * afterwards - the persistence context returns the instance it already holds. The update path
     * therefore pre-checks existence with {@link #findDefaultCategoryTypeById}, which answers a
     * question rather than loading the row, and reaches this method only once the write has landed.
     *
     * <p>{@code user_id IS NULL} is the whole filter, and it is the authorisation-relevant part: an
     * id belonging to a student's own category returns empty, so this method cannot be used to read
     * a row that is not a default. BR-06 is what that protects, and having the predicate in the query
     * rather than in a caller's assertion is the version of it that cannot be forgotten.
     *
     * <p>Accepts a retired row, because BR-07's remedy for a retired category is to set
     * {@code isActive} back to true, which means an administrator must be able to read one.
     */
    @Query("""
            SELECT c FROM Category c
             WHERE c.id = :id AND c.userId IS NULL
            """)
    Optional<Category> findDefaultById(@Param("id") Long id);

    /**
     * The type of a shared default category, or empty when the id belongs to a student's own category
     * or to nothing.
     *
     * <p>A scalar projection, not an entity read, and that is the point: UC-20's update path needs two
     * facts <em>before</em> the write - does this default category exist, and what type does it have -
     * and it needs the row <em>after</em> the write, to describe what was stored. Loading the entity
     * first would put it in the persistence context, and the read-back would then return that same
     * stale instance, because the procedure's {@code UPDATE} is native SQL that Hibernate never sees.
     * Selecting one column hydrates nothing, so the read-back is the first time this request loads the
     * row, and one query answers both questions.
     *
     * <p>The type is needed because it decides how a refusal is reported. If the request changes it,
     * {@code trg_categories_before_update} refuses the write when any transaction, budget or recurring
     * rule references the category - and it raises the same SQLSTATE 45000 that {@code sp_require_admin}
     * and the procedure itself raise. Comparing the stored type with the requested one lets the service
     * tell that case apart without matching anyone's prose, and it needs the stored value to do it.
     *
     * <p>{@code user_id IS NULL} is in the query, so an id belonging to a student's own category takes
     * the same path as an id belonging to nothing - BR-06, and neither answer says which it was.
     */
    @Query("""
            SELECT c.type FROM Category c
             WHERE c.id = :id AND c.userId IS NULL
            """)
    Optional<CategoryType> findDefaultCategoryTypeById(@Param("id") Long id);

    /**
     * The shared default category of this type with this name, or empty when there is none.
     *
     * <p>The natural-key read-back for UC-20's create path. The procedure has no OUT parameter and
     * writes its {@code admin_audit_log} row after the category row, so {@code LAST_INSERT_ID()}
     * after the call reports the audit row's id, not the created category's - the created row has to
     * be located by something unique about it instead. This pair is unique among default rows because
     * {@code uk_categories_scope_type_name} says so, which is the same index that refuses a duplicate
     * name, so the read cannot match one row that the insert did not just make.
     *
     * <p>An explicit query rather than a derived name, because the two predicates are
     * {@code user_id IS NULL} and a name, and the derived form for a null-check plus an ordering plus
     * a limiting keyword is a method name long enough to be misread. The SQL says the same thing in
     * one line.
     */
    @Query("""
            SELECT c FROM Category c
             WHERE c.userId IS NULL AND c.type = :type AND c.name = :name
             ORDER BY c.id ASC
            """)
    List<Category> findDefaultsByTypeAndName(@Param("type") CategoryType type,
                                             @Param("name") String name);
}
