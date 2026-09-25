package com.campuscoin.admin.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.dto.UpdateDefaultCategoryRequest;
import com.campuscoin.admin.dto.UpsertDefaultCategoryRequest;
import com.campuscoin.admin.repository.AdminCategoryProcedureDao;
import com.campuscoin.category.dto.CategoryResponse;
import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.category.mapper.CategoryMapper;
import com.campuscoin.category.repository.CategoryRepository;
import com.campuscoin.common.exception.CategoryInUseException;
import com.campuscoin.common.exception.CategoryNameTakenException;
import com.campuscoin.common.exception.DataConflictException;
import com.campuscoin.common.exception.NotFoundException;

/**
 * The shared default categories every student can choose from (UC-20, BR-06).
 *
 * <p><b>This class does not own default categories the way {@code CategoryService} owns a student's
 * categories.</b> It reaches no entity that can write the table: the list is read through a
 * projection query on {@link CategoryRepository} and both writes are {@code CALL}s to
 * {@code sp_admin_upsert_default_category}, which is the only writer of {@code user_id IS NULL} rows
 * in this build. The reason is BR-06's authorisation gate - the procedure calls
 * {@code sp_require_admin} and writes an audit row, and a {@code JpaRepository#save} would do
 * neither, because the application's database account holds direct table grants.
 *
 * <p><b>Why the read is here and not in an {@code AdminCategoryViewDao}.</b> {@code categories} is
 * module 3's table and {@code CategoryRepository} already speaks it, including the two queries UC-20
 * needs. A second repository interface over the same table would be a second statement of its shape,
 * and this module has no other reason to read {@code categories} in bulk. The two methods it uses
 * are documented there as written for this screen.
 *
 * <p><b>The response is the student module's {@link CategoryResponse}, not a new admin model.</b> A
 * default category is the same thing to an administrator as to a student - a name, a type, an icon, a
 * colour and whether it is retired - and {@code isDefault} is already true on every row this
 * controller returns. A second DTO would be a second definition of the same row that could disagree
 * with the first.
 *
 * <p><b>The response contains no owner.</b> {@code CategoryMapper} drops {@code userId} and
 * {@code createdBy}, which is what makes reusing it safe here: there is no field through which an
 * administrator could see, or send back, whose row this is.
 */
@Service
public class AdminCategoryService {

    private static final Logger log = LoggerFactory.getLogger(AdminCategoryService.class);

    /**
     * What a refused write says when the refusal is not a duplicate name.
     *
     * <p>The remaining 45000 on the update path means the row stopped being a default category between
     * the pre-read and the write - {@code sp_admin_upsert_default_category} signals it when its own
     * {@code user_id IS NULL} lookup finds nothing. It is classified as a conflict rather than a
     * {@code 404} because the pre-read already answered "no such default category" once, and a second
     * answer to a question the caller already passed would be telling them their id was wrong when it
     * was not.
     */
    private static final String RELOAD_AND_RETRY =
            "The default category could not be changed. Reload it and try again.";

    private final CategoryRepository categoryRepository;
    private final AdminCategoryProcedureDao categoryProcedureDao;
    private final CategoryMapper categoryMapper;

    public AdminCategoryService(CategoryRepository categoryRepository,
                                AdminCategoryProcedureDao categoryProcedureDao,
                                CategoryMapper categoryMapper) {
        this.categoryRepository = categoryRepository;
        this.categoryProcedureDao = categoryProcedureDao;
        this.categoryMapper = categoryMapper;
    }

    /**
     * UC-20: every shared default category, retired ones included.
     *
     * <p>Retired rows are listed on purpose: {@code isActive = false} is how BR-07 withdraws a
     * category from students without deleting it, and a retired row is exactly the one an
     * administrator needs to find in order to bring it back. Hiding it would make the flag
     * unreachable and retirement a one-way door.
     */
    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        return categoryRepository.findAllDefaults().stream()
                .map(categoryMapper::toResponse)
                .toList();
    }

    /**
     * UC-20: create a shared default category.
     *
     * <p>A name already used by another default of the same type is answered as
     * {@code CATEGORY_NAME_TAKEN} - the same code, and nearly the same message, the student module
     * gives - because the caller's remedy is the same: choose another name. The database's guarantee
     * is {@code uk_categories_scope_type_name}, not {@code trg_categories_before_insert}: that
     * trigger's duplicate check applies only to rows with an owner, so an administrator creating a
     * default row is refused by the index. The query below runs first only so the message is precise.
     *
     * <p><b>Read back by natural key, not by last insert id.</b> The procedure has no OUT parameter,
     * and it writes its {@code admin_audit_log} row after the category row, so
     * {@code LAST_INSERT_ID()} after the call reports the audit row. The unique key
     * {@code (user_id IS NULL, type, name)} identifies the row that was just created, within the same
     * transaction, so the read cannot see a different one.
     */
    @Transactional
    public CategoryResponse create(UpsertDefaultCategoryRequest request, Long actorId, String ipAddress) {
        String name = request.name().trim();
        CategoryType type = request.type();

        if (categoryRepository.existsByUserIdIsNullAndTypeAndName(type, name)) {
            throw nameTaken(name);
        }

        try {
            categoryProcedureDao.upsertDefaultCategory(
                    actorId,
                    null,
                    name,
                    type,
                    trimToNull(request.icon()),
                    trimToNull(request.color()),
                    trimToNull(request.description()),
                    request.sortOrder(),
                    request.isActive(),
                    ipAddress);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, name, false);
        }

        log.info("Default category created actorId={} type={} name={}", actorId, type, name);

        return readBackByNaturalKey(type, name);
    }

    /**
     * UC-20: change a shared default category.
     *
     * <p><b>A null field means "leave it alone", and that is the procedure's rule, not this class's
     * choice.</b> {@code sp_admin_upsert_default_category} writes every column as
     * {@code IFNULL(p_x, x)}, so a null cannot clear a value - it preserves the stored one. Passing
     * the request's fields through unchanged is therefore the correct mapping, and the DTO's own
     * constraints say so: an absent or blank icon is refused rather than treated as "remove it".
     * Clearing a default category's icon is not possible through this endpoint, and
     * {@code UpdateDefaultCategoryRequest} states that on the fields it applies to.
     *
     * <p><b>The row is read first, and that is what makes the refusal classifiable.</b>
     * {@code sp_admin_upsert_default_category}'s update branch signals SQLSTATE 45000 with
     * 'BR-06: default category to update was not found' when the row is not a default one, and
     * {@code sp_require_admin} uses the same SQLSTATE for its own refusals. Reading first answers
     * {@code 404} for an unknown or non-default id without matching the procedure's prose, and leaves
     * the surviving 45000 meaning "the row changed underneath this request".
     *
     * <p>A duplicate name is not pre-checked here, unlike on the create path, because
     * {@code trg_categories_before_update} deliberately does not check names - the rename is refused by
     * {@code uk_categories_scope_type_name} at the write, and the classifier reads it by constraint
     * name. Pre-checking would need an exclusion for this row's own id, and it would still not be the
     * authority.
     *
     * @throws NotFoundException      no default category has this id
     * @throws CategoryNameTakenException another default category of the same type uses the new name
     */
    @Transactional
    public CategoryResponse update(Long categoryId, UpdateDefaultCategoryRequest request,
                                   Long actorId, String ipAddress) {
        // Pre-check: a scalar read, so nothing is hydrated before the write. That matters because the
        // response is read back afterwards, and a category already in the persistence context would be
        // returned unchanged - the procedure's UPDATE is native SQL that Hibernate never sees.
        CategoryType storedType = requireDefaultCategoryType(categoryId);
        boolean changesType = request.type() != null && request.type() != storedType;

        String name = trimToNull(request.name());

        try {
            categoryProcedureDao.upsertDefaultCategory(
                    actorId,
                    categoryId,
                    name,
                    request.type(),
                    trimToNull(request.icon()),
                    trimToNull(request.color()),
                    trimToNull(request.description()),
                    request.sortOrder(),
                    request.isActive(),
                    ipAddress);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, name, changesType);
        }

        log.info("Default category updated actorId={} categoryId={}", actorId, categoryId);

        return categoryRepository.findDefaultById(categoryId)
                .map(categoryMapper::toResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "Default category " + categoryId + " was not readable back after update."));
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /**
     * The stored type of the default category with this id, or the {@code 404} that says there is none.
     *
     * <p>The type is returned as well as checked because the refusal it can produce is a different one:
     * see {@link #translateWriteFailure}. Selecting the single column rather than loading the entity is
     * what keeps the read-back after the write honest - see {@link #update}.
     *
     * <p>{@code user_id IS NULL} is in the query, so an id belonging to a student's own category takes
     * the same path as an id belonging to nothing. BR-06 is what that protects, and neither answer tells
     * the caller which of the two it was.
     */
    private CategoryType requireDefaultCategoryType(Long categoryId) {
        return categoryRepository.findDefaultCategoryTypeById(categoryId)
                .orElseThrow(() -> new NotFoundException("Default category not found."));
    }

    /**
     * The row just created, located by its unique key rather than by the session's last insert id.
     *
     * <p>That key is {@code (type, name)} among default rows, and nothing else can occupy it - the
     * unique index that admitted this insert is the same one that refuses a duplicate name. Reading by
     * id is not available to this path, because the id was never returned: the procedure has no OUT
     * parameter and its audit row is inserted after the category row, so
     * {@code LAST_INSERT_ID()} reports the audit row.
     *
     * <p>The list form is used rather than a single-result method so that an impossible second match
     * would be a deterministic choice rather than a {@code NonUniqueResultException} surfacing as a
     * server error; the query orders by id for that reason.
     */
    private CategoryResponse readBackByNaturalKey(CategoryType type, String name) {
        return categoryRepository.findDefaultsByTypeAndName(type, name).stream()
                .findFirst()
                .map(categoryMapper::toResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "The default category '" + name + "' was not readable back after creation."));
    }

    /**
     * Turns a database refusal into the API error that explains it.
     *
     * <p>The duplicate is asked about first, by constraint name: every duplicate is also an integrity
     * violation, and the specific answer is the one the caller can act on.
     *
     * <p><b>The remaining 45000 is narrowed by what the request asked for, not by the procedure's
     * wording.</b> Only one of {@code sp_admin_upsert_default_category}'s refusals can be reached once
     * the pre-check has answered existence:
     *
     * <ul>
     *   <li>if the request changes the type, and any transaction, budget or recurring rule references
     *       the category, {@code trg_categories_before_update} refuses it - {@code CATEGORY_IN_USE},
     *       the same code the student module returns for the same rule, because the caller's remedy is
     *       the same: leave the type alone or make a new category;</li>
     *   <li>otherwise the row stopped being a default one between the pre-check and the write, or
     *       {@code sp_require_admin} refused the actor - a conflict, because the caller's id and body
     *       were both correct a moment ago.</li>
     * </ul>
     *
     * <p>The signalled branch is asked before the constraint branch because the trigger routes the
     * category-in-use refusal through SQLSTATE 45000, not 23000 - a CHECK or a restricting foreign key
     * is what 23000 means here, and neither is reachable once existence is known.
     */
    private RuntimeException translateWriteFailure(RuntimeException ex, String name, boolean changesType) {
        if (AdminWriteFailure.isDuplicateDefaultCategory(ex)) {
            log.info("Default category name taken name={}", name);
            return nameTaken(name);
        }

        if (AdminWriteFailure.isSignalledRefusal(ex)) {
            if (changesType) {
                log.info("Default category type change refused name={}", name);
                return new CategoryInUseException(
                        "This category is used by transactions, budgets or recurring rules, so its "
                                + "type cannot change. Create a new category instead.");
            }
            log.info("Default category write refused by a procedure signal name={}", name);
            return new DataConflictException(RELOAD_AND_RETRY);
        }

        if (AdminWriteFailure.isConstraintViolation(ex)) {
            log.info("Default category write refused by a constraint name={}", name);
            return new DataConflictException(RELOAD_AND_RETRY);
        }

        log.error("Default category write failed unexpectedly name={}", name, ex);
        return ex;
    }

    private CategoryNameTakenException nameTaken(String name) {
        return new CategoryNameTakenException(
                "A default category of this type already uses the name '" + name + "'.");
    }

    /** Absent or whitespace-only becomes null, which the procedure reads as "leave it unchanged". */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
