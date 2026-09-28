package com.campuscoin.category.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.category.dto.CategoryResponse;
import com.campuscoin.category.entity.Category;

@Component
public class CategoryMapper {

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
