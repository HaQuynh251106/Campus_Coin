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

@Service
public class AdminCategoryService {

    private static final Logger log = LoggerFactory.getLogger(AdminCategoryService.class);

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

    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        return categoryRepository.findAllDefaults().stream()
                .map(categoryMapper::toResponse)
                .toList();
    }

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

    @Transactional
    public CategoryResponse update(Long categoryId, UpdateDefaultCategoryRequest request,
                                   Long actorId, String ipAddress) {

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

    private CategoryType requireDefaultCategoryType(Long categoryId) {
        return categoryRepository.findDefaultCategoryTypeById(categoryId)
                .orElseThrow(() -> new NotFoundException("Default category not found."));
    }

    private CategoryResponse readBackByNaturalKey(CategoryType type, String name) {
        return categoryRepository.findDefaultsByTypeAndName(type, name).stream()
                .findFirst()
                .map(categoryMapper::toResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "The default category '" + name + "' was not readable back after creation."));
    }

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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
