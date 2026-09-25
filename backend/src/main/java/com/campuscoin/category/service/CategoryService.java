package com.campuscoin.category.service;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.category.dto.CategoryResponse;
import com.campuscoin.category.dto.CreateCategoryRequest;
import com.campuscoin.category.dto.UpdateCategoryRequest;
import com.campuscoin.category.entity.Category;
import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.category.mapper.CategoryMapper;
import com.campuscoin.category.repository.CategoryRepository;
import com.campuscoin.common.exception.CategoryInUseException;
import com.campuscoin.common.exception.CategoryNameTakenException;
import com.campuscoin.common.exception.NotFoundException;

/**
 * Personal categories (UC-06).
 *
 * <p>Reads the list a student can choose from, and creates, edits, retires and deletes the rows the
 * student owns. Default categories are visible but read-only here: they belong to everyone and only
 * an administrator may change one (BR-06, module 11).
 *
 * <p>There is no stored procedure for personal categories. The database's
 * {@code sp_admin_upsert_default_category} is the administrator's route and calls
 * {@code sp_require_admin} first, so it is the wrong tool for a student writing their own row -
 * reusing it would mean either granting the student the admin gate or calling a procedure whose
 * first act is to verify a privilege the caller does not have. So the writes are the application's
 * (section 5), and the rules the database does own are left where they are:
 *
 * <ul>
 *   <li><b>Name uniqueness within a student's own categories</b> -
 *       {@code uk_categories_scope_type_name} on {@code (scope_key, type, name)}. The service asks
 *       first so the caller gets a field-level error, and catches the constraint for the race.</li>
 *   <li><b>A personal category may not shadow a default one</b> -
 *       {@code trg_categories_before_insert}. The service applies the same check on the rename
 *       path, where no trigger covers it - see {@link #update}.</li>
 *   <li><b>A category may not change scope</b> - {@code trg_categories_before_update}. Java never
 *       writes {@code user_id} on an existing row, so this is structural rather than a check.</li>
 *   <li><b>A referenced category may not change type</b> - {@code trg_categories_before_update}.</li>
 *   <li><b>A category in use may not be deleted</b> - {@code fk_txn_category} and
 *       {@code fk_recurring_category} restrict, and {@code trg_categories_before_delete} adds the
 *       budget case.</li>
 * </ul>
 *
 * <p>The type-change and delete rules span {@code transactions}, {@code budgets} and
 * {@code recurring_rules}, which belong to modules 4 to 6. Asking about them from here would mean
 * this package depending on entities it has no business knowing, and it would restate a rule that
 * already holds for hand-run statements. So they are left to the database and translated on the way
 * out, by SQLSTATE rather than by parsing the driver's text.
 */
@Service
public class CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    /** Stands in for "no row to exclude" on the create path; ids are unsigned and never negative. */
    private static final long NO_EXCLUSION = -1L;

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    public CategoryService(CategoryRepository categoryRepository, CategoryMapper categoryMapper) {
        this.categoryRepository = categoryRepository;
        this.categoryMapper = categoryMapper;
    }

    /**
     * UC-06: every category the caller can choose from - their own and the shared defaults.
     *
     * <p>{@code readOnly = true} documents that nothing is written. The account comes from the
     * verified token and the method takes no user id, so there is no way to ask for somebody else's
     * list.
     */
    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories(AuthenticatedUser principal) {
        return categoryRepository.findVisibleToUser(principal.userId()).stream()
                .map(categoryMapper::toResponse)
                .toList();
    }

    /**
     * UC-06: read one of the caller's own categories.
     *
     * <p>Exists so a client can refresh a single row after a change without reloading the list. A
     * default category, or another student's, is not found - the two are indistinguishable from the
     * outside, on purpose.
     *
     * @throws NotFoundException if the category does not exist or is not the caller's own
     */
    @Transactional(readOnly = true)
    public CategoryResponse getCategory(AuthenticatedUser principal, Long categoryId) {
        return categoryMapper.toResponse(requireOwnCategory(principal, categoryId));
    }

    /**
     * UC-06: create a personal category owned by the caller.
     *
     * <p>The two name rules are checked before the insert so the caller receives an error naming the
     * reason. The database enforces both as well, and its check is the authority: a request that
     * slips past the pre-check is caught below and answered with the same error rather than a
     * generic conflict.
     *
     * @throws CategoryNameTakenException if the caller already has this name for this type, or a
     *                                    default category uses it
     */
    @Transactional
    public CategoryResponse create(AuthenticatedUser principal, CreateCategoryRequest request) {
        Long userId = principal.userId();
        String name = request.name().trim();

        requireNameIsFree(userId, request.type(), name, NO_EXCLUSION);

        Category category = Category.newPersonal(
                userId,
                name,
                request.type(),
                trimToNull(request.icon()),
                upperCaseOrNull(trimToNull(request.color())),
                trimToNull(request.description()),
                request.sortOrder(),
                request.isActive());

        try {
            categoryRepository.saveAndFlush(category);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, userId, name, WriteOperation.CREATE);
        }

        log.info("Category created userId={} categoryId={}", userId, category.getId());
        return categoryMapper.toResponse(category);
    }

    /**
     * UC-06: change some fields of one of the caller's own categories.
     *
     * <p>Only the fields actually present are touched, so a request that sends one field leaves the
     * others alone. A request that changes nothing modifies no field, so Hibernate's dirty checking
     * finds nothing to write and no UPDATE is issued.
     *
     * <p>Clearing: sending {@code ""} for {@code icon}, {@code color} or {@code description} stores
     * null. Leaving a field out, or sending null, leaves it as it is.
     *
     * <p>The name is checked against the type the row will have <em>after</em> this request, because
     * a rename and a type change arriving together would otherwise be judged against the old type
     * and collide once applied.
     *
     * <p>One check is the application's rather than the database's: that a rename does not take a
     * default category's name. {@code trg_categories_before_insert} enforces it on insert, but the
     * update trigger checks only scope and type, so a rename could reach the very state the rule
     * exists to prevent. Applying the same check here needs no schema change and closes that gap
     * instead of inventing a rule - the rationale is the one already written in the trigger.
     *
     * @throws NotFoundException          if the category is not the caller's own
     * @throws CategoryNameTakenException if the new name collides
     * @throws CategoryInUseException     if the category is referenced and its type is changing
     */
    @Transactional
    public CategoryResponse update(AuthenticatedUser principal, Long categoryId,
                                   UpdateCategoryRequest request) {
        Category category = requireOwnCategory(principal, categoryId);

        // The type the row will have once this request is applied. Uniqueness has to be judged
        // against that, not against the current type.
        CategoryType resultingType = request.type() != null ? request.type() : category.getType();
        String resultingName = request.name() != null ? request.name().trim() : category.getName();

        if (!resultingName.equals(category.getName())
                || !resultingType.equals(category.getType())) {
            requireNameIsFree(category.getUserId(), resultingType, resultingName, category.getId());
        }

        if (request.name() != null) {
            category.setName(resultingName);
        }
        if (request.type() != null) {
            category.setType(request.type());
        }
        if (request.icon() != null) {
            category.setIcon(trimToNull(request.icon()));
        }
        if (request.color() != null) {
            category.setColor(upperCaseOrNull(trimToNull(request.color())));
        }
        if (request.description() != null) {
            category.setDescription(trimToNull(request.description()));
        }
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        if (request.isActive() != null) {
            category.setIsActive(request.isActive());
        }

        try {
            categoryRepository.saveAndFlush(category);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, category.getUserId(), resultingName, WriteOperation.UPDATE);
        }

        log.info("Category updated userId={} categoryId={}", principal.userId(), categoryId);
        return categoryMapper.toResponse(category);
    }

    /**
     * UC-06 / BR-07: delete one of the caller's own categories.
     *
     * <p>Deletion is permitted only while nothing points at the category. Once a transaction, a
     * budget or a recurring rule references it the database refuses, and BR-07's answer is to retire
     * it instead - set {@code isActive} to false through {@link #update}. Deleting would strip those
     * records of their category, and the reporting views join through it.
     *
     * @throws NotFoundException      if the category is not the caller's own
     * @throws CategoryInUseException if the category is referenced by anything
     */
    @Transactional
    public void delete(AuthenticatedUser principal, Long categoryId) {
        Category category = requireOwnCategory(principal, categoryId);

        try {
            categoryRepository.delete(category);
            categoryRepository.flush();
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, category.getUserId(), category.getName(),
                    WriteOperation.DELETE);
        }

        log.info("Category deleted userId={} categoryId={}", principal.userId(), categoryId);
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /** Which call site is translating a failure, since the same constraint means different things. */
    private enum WriteOperation {
        CREATE,
        UPDATE,
        DELETE
    }

    /**
     * Turns a database refusal into the API error that explains it.
     *
     * <p>Which rule fired is decided by {@link CategoryWriteFailure}, which asks the database by
     * SQLSTATE and by constraint name rather than by matching the driver's message. Anything it does
     * not recognise is rethrown unchanged, so {@code GlobalExceptionHandler} answers it as a generic
     * conflict or an internal error rather than this method mislabelling it.
     *
     * <p>The exception is deliberately not logged. MySQL's duplicate-key message carries the
     * constraint name and the scope key, and a trigger's message names the table it guards - all
     * internal identifiers the response already withholds. The operation and the user id describe
     * the refusal well enough to investigate it, and the triggering request is reproducible, so
     * nothing diagnostic is lost by keeping the driver's text out of the log.
     *
     * @param userId the owning student, for the log line - never the message
     * @param name   the category name involved, for the same reason
     */
    private RuntimeException translateWriteFailure(RuntimeException ex, Long userId, String name,
                                                   WriteOperation operation) {
        if (CategoryWriteFailure.isUniqueNameViolation(ex)) {
            log.info("Category write rejected by the unique name constraint userId={} op={}",
                    userId, operation);
            return nameTakenForThisStudent(name);
        }

        if (CategoryWriteFailure.isSignalledRefusal(ex)) {
            // A trigger refused the write. Which rule fired follows from the operation, because
            // each path reaches only one SIGNAL:
            //   CREATE - trg_categories_before_insert, and this path always sets user_id, so the
            //            admin-gate branch is unreachable; the shadowing rule is the only other one.
            //   UPDATE - trg_categories_before_update signals on a scope change, which no setter
            //            here can cause, or on a type change to a referenced category.
            //   DELETE - trg_categories_before_delete, which refuses a category that has a budget.
            log.info("Category write rejected by a trigger userId={} op={}", userId, operation);
            return switch (operation) {
                case CREATE -> defaultNameTaken(name);
                case UPDATE, DELETE -> categoryInUse(operation);
            };
        }

        if (CategoryWriteFailure.isConstraintViolation(ex)) {
            // A restricting foreign key: something still points at the category being deleted.
            log.info("Category write rejected by a foreign key userId={} op={}", userId, operation);
            return categoryInUse(operation);
        }

        // Not one of the three recognised refusals, so it is a genuine fault rather than a rule
        // doing its job. Logged in full and answered as an internal error by the handler.
        log.error("Category write failed unexpectedly userId={} op={}", userId, operation, ex);
        return ex;
    }

    /**
     * Loads a category that the caller owns.
     *
     * <p>The query takes the caller's id, so a category belonging to another student - or a shared
     * default one, which belongs to nobody - is simply not found. Both cases give the same answer,
     * which is what stops a client from using this endpoint to discover which category ids exist.
     * BR-02 requires ownership to be enforced server-side; doing it in the query rather than in a
     * comparison afterwards is what makes it impossible to forget.
     */
    private Category requireOwnCategory(AuthenticatedUser principal, Long categoryId) {
        return categoryRepository.findByIdAndUserId(categoryId, principal.userId())
                .orElseThrow(() -> new NotFoundException("Category not found."));
    }

    /**
     * Refuses a name that is already taken, by the caller's own categories or by a default one.
     *
     * <p>The database repeats both checks. They are here so the caller receives an error that says
     * which name clashes with what, because neither the constraint nor the trigger can distinguish
     * those two cases for the client and their messages are not part of the API.
     */
    private void requireNameIsFree(Long userId, CategoryType type, String name, long excludedId) {
        if (categoryRepository.existsByUserIdAndTypeAndNameAndIdNot(userId, type, name, excludedId)) {
            throw nameTakenForThisStudent(name);
        }
        if (categoryRepository.existsByUserIdIsNullAndTypeAndName(type, name)) {
            throw defaultNameTaken(name);
        }
    }

    private CategoryNameTakenException nameTakenForThisStudent(String name) {
        return new CategoryNameTakenException(
                "You already have a category named \"" + name + "\" of this type.");
    }

    private CategoryNameTakenException defaultNameTaken(String name) {
        return new CategoryNameTakenException(
                "\"" + name + "\" is the name of a standard category and cannot be reused.");
    }

    private CategoryInUseException categoryInUse(WriteOperation operation) {
        return new CategoryInUseException(operation == WriteOperation.DELETE
                ? "This category is still used by a transaction, a budget or a recurring rule. "
                        + "Disable it instead of deleting it, so the existing records keep their "
                        + "category."
                : "This category is already used by a transaction, a budget or a recurring rule, "
                        + "so its type cannot be changed. Create a new category instead.");
    }

    /** Trims, and turns a value that is empty after trimming into null. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Trims to null, then upper-cases - the form every hex colour in the seed and mock data uses. */
    private static String upperCaseOrNull(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
