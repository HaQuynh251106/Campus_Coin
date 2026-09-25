package com.campuscoin.category.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.category.dto.CategoryResponse;
import com.campuscoin.category.entity.Category;

/**
 * Maps a {@link Category} row to the API model (UC-06).
 *
 * <p>Its own class, and a plain one rather than a mapping library, for the same reason
 * {@code ProfileMapper} is: it is the single place that decides which columns may leave the
 * server. The entity carries {@code userId} and {@code createdBy} - database bookkeeping the
 * client has no use for and must never be tempted to send back - and centralising the choice here
 * means a future field can be added to the entity without it silently appearing in a response.
 *
 * <p>{@code isDefault} is derived from {@code userId} rather than mapped from a column, because
 * the schema has no such column: a category is a default one exactly when it belongs to nobody.
 * Deriving it here keeps that definition in one place instead of repeating the null test in
 * whichever controller happened to need it.
 */
@Component
public class CategoryMapper {

    /**
     * UC-06: one category, personal or default, as the client sees it.
     *
     * <p>Deliberately omitted: {@code userId}, {@code createdBy}, {@code createdAt} and
     * {@code updatedAt}. The first two identify accounts, and the timestamps are not part of the
     * category screen.
     */
    public CategoryResponse toResponse(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getType(),
                category.getIcon(),
                category.getColor(),
                category.isDefault(),
                category.getIsActive(),
                category.getSortOrder(),
                category.getDescription());
    }
}
