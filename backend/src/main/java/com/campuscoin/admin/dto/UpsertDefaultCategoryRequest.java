package com.campuscoin.admin.dto;

import com.campuscoin.category.entity.CategoryType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "A shared default category to create (UC-20).")
public record UpsertDefaultCategoryRequest(

        @Schema(description = "Display name. Cannot be blank, and is unique among default "
                + "categories of the same type.", example = "Coffee & Snacks")
        @NotNull(message = "Name is required.")
        @Pattern(regexp = "^\\s*\\S.{0,79}\\s*$",
                message = "Name must be between 1 and 80 characters and cannot be blank.")
        String name,

        @Schema(description = "Whether records filed here are income or expense (BR-05).",
                example = "EXPENSE", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Type is required.")
        CategoryType type,

        @Schema(description = "Icon name for the client to resolve. Optional.",
                example = "coffee", nullable = true)
        @Pattern(regexp = "^\\s*.{0,50}\\s*$",
                message = "Icon must be at most 50 characters.")
        String icon,

        @Schema(description = "Hex colour, exactly #RRGGBB. Optional.", example = "#F59E0B",
                nullable = true)
        @Pattern(regexp = "^\\s*(#[0-9A-Fa-f]{6})?\\s*$",
                message = "Colour must be a hex value such as #F59E0B.")
        String color,

        @Schema(description = "Optional note.", example = "Boba and study snacks", nullable = true)
        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Description must be at most 255 characters.")
        String description,

        @Schema(description = "Display order within the type. The database default is 0.",
                example = "0", nullable = true)
        @Min(value = 0, message = "Sort order cannot be negative.")
        @Max(value = 32767, message = "Sort order must be at most 32767.")
        Integer sortOrder,

        @Schema(description = "False creates the category already retired, so students are not "
                + "offered it (BR-07). The database default is true.", example = "true",
                nullable = true)
        Boolean isActive) {
}
