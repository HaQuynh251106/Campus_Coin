package com.campuscoin.category.dto;

import com.campuscoin.category.entity.CategoryType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@Schema(description = "The category fields to change. Omit a field to leave it as it is (UC-06).")
public record UpdateCategoryRequest(

        @Schema(description = "New display name. Omit to leave it unchanged; it cannot be blank.",
                example = "Coffee & Snacks", nullable = true)

        @Pattern(regexp = "^\\s*\\S.{0,79}\\s*$",
                message = "Name must be between 1 and 80 characters and cannot be blank.")
        String name,

        @Schema(description = "New type. Refused once any transaction, budget or recurring rule "
                + "uses the category, because it would rewrite the meaning of that history.",
                example = "EXPENSE", nullable = true)
        CategoryType type,

        @Schema(description = "Icon name. Send an empty string to clear it.",
                example = "coffee", nullable = true)
        @Pattern(regexp = "^\\s*.{0,50}\\s*$",
                message = "Icon must be at most 50 characters.")
        String icon,

        @Schema(description = "Hex colour, exactly #RRGGBB. Send an empty string to clear it.",
                example = "#F59E0B", nullable = true)

        @Pattern(regexp = "^\\s*(#[0-9A-Fa-f]{6})?\\s*$",
                message = "Colour must be a hex value such as #F59E0B.")
        String color,

        @Schema(description = "Note. Send an empty string to clear it.",
                example = "Boba and study snacks", nullable = true)

        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Description must be at most 255 characters.")
        String description,

        @Schema(description = "New display order.", example = "0", nullable = true)
        @Min(value = 0, message = "Sort order cannot be negative.")
        @Max(value = 32767, message = "Sort order must be at most 32767.")
        Integer sortOrder,

        @Schema(description = "False retires the category without deleting it (BR-07). True "
                + "brings it back.", example = "false", nullable = true)
        Boolean isActive) {
}
