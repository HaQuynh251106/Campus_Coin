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

@Service
public class CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    private static final long NO_EXCLUSION = -1L;

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    public CategoryService(CategoryRepository categoryRepository, CategoryMapper categoryMapper) {
        this.categoryRepository = categoryRepository;
        this.categoryMapper = categoryMapper;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories(AuthenticatedUser principal) {
        return categoryRepository.findVisibleToUser(principal.userId()).stream()
                .map(categoryMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse getCategory(AuthenticatedUser principal, Long categoryId) {
        return categoryMapper.toResponse(requireOwnCategory(principal, categoryId));
    }

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

    @Transactional
    public CategoryResponse update(AuthenticatedUser principal, Long categoryId,
                                   UpdateCategoryRequest request) {
        Category category = requireOwnCategory(principal, categoryId);

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

    private enum WriteOperation {
        CREATE,
        UPDATE,
        DELETE
    }

    private RuntimeException translateWriteFailure(RuntimeException ex, Long userId, String name,
                                                   WriteOperation operation) {
        if (CategoryWriteFailure.isUniqueNameViolation(ex)) {
            log.info("Category write rejected by the unique name constraint userId={} op={}",
                    userId, operation);
            return nameTakenForThisStudent(name);
        }

        if (CategoryWriteFailure.isSignalledRefusal(ex)) {

            log.info("Category write rejected by a trigger userId={} op={}", userId, operation);
            return switch (operation) {
                case CREATE -> defaultNameTaken(name);
                case UPDATE, DELETE -> categoryInUse(operation);
            };
        }

        if (CategoryWriteFailure.isConstraintViolation(ex)) {

            log.info("Category write rejected by a foreign key userId={} op={}", userId, operation);
            return categoryInUse(operation);
        }

        log.error("Category write failed unexpectedly userId={} op={}", userId, operation, ex);
        return ex;
    }

    private Category requireOwnCategory(AuthenticatedUser principal, Long categoryId) {
        return categoryRepository.findByIdAndUserId(categoryId, principal.userId())
                .orElseThrow(() -> new NotFoundException("Category not found."));
    }

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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String upperCaseOrNull(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
