package com.campuscoin.admin.dto;

import com.campuscoin.category.entity.CategoryType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@Schema(description = "The default category fields to change. Omit a field to leave it as it is "
        + "(UC-20).")
public record UpdateDefaultCategoryRequest(

        @Schema(description = "New display name. Omit to leave it unchanged; it cannot be blank.",
                example = "Coffee & Snacks", nullable = true)
        @Pattern(regexp = "^\\s*\\S.{0,79}\\s*$",
                message = "Name must be between 1 and 80 characters and cannot be blank.")
        String name,

        @Schema(description = "New type. Refused once any transaction, budget or recurring rule "
                + "uses the category, because it would rewrite the meaning of that history.",
                example = "EXPENSE", nullable = true)
        CategoryType type,

        @Schema(description = "New icon name. Omit to leave it unchanged. A default category's "
                + "icon cannot be cleared through this endpoint; see the endpoint's description.",
                example = "coffee", nullable = true)

        @Pattern(regexp = "^\\s*\\S.{0,49}\\s*$",
                message = "Icon must be between 1 and 50 characters. Omit it to leave the current "
                        + "icon unchanged; it cannot be cleared through this endpoint.")
        String icon,

        @Schema(description = "New hex colour, exactly #RRGGBB. Omit to leave it unchanged.",
                example = "#F59E0B", nullable = true)
        @Pattern(regexp = "^\\s*#[0-9A-Fa-f]{6}\\s*$",
                message = "Colour must be a hex value such as #F59E0B. Omit it to leave the "
                        + "current colour unchanged; it cannot be cleared through this endpoint.")
        String color,

        @Schema(description = "New note. Omit to leave it unchanged.",
                example = "Boba and study snacks", nullable = true)
        @Pattern(regexp = "(?s)^\\s*\\S.{0,254}\\s*$",
                message = "Description must be between 1 and 255 characters. Omit it to leave the "
                        + "current note unchanged; it cannot be cleared through this endpoint.")
        String description,

        @Schema(description = "New display order.", example = "0", nullable = true)
        @Min(value = 0, message = "Sort order cannot be negative.")
        @Max(value = 32767, message = "Sort order must be at most 32767.")
        Integer sortOrder,

        @Schema(description = "False retires the category without deleting it (BR-07). True "
                + "brings it back. This is also how a default category is withdrawn from students' "
                + "pickers.", example = "false", nullable = true)
        Boolean isActive) {
}
