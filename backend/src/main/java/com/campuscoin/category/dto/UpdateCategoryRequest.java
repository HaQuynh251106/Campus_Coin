package com.campuscoin.category.dto;

import com.campuscoin.category.entity.CategoryType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code PATCH /api/v1/categories/{id}} (UC-06): change some fields of one of the
 * student's own categories.
 *
 * <p>Every field is optional and a field left out is not changed, so a client sends only what it
 * edited. An explicit {@code null} means the same thing. That leaves the three nullable text
 * fields - {@code icon}, {@code color} and {@code description} - needing a way to be cleared, and
 * they use the empty string for it: sending {@code ""} stores null. The three that cannot be
 * absent are different: {@code name} and {@code type} have no empty state, so a blank there is a
 * validation error rather than a silent no-op, and {@code isActive} is a boolean that is either
 * sent or not.
 *
 * <p>This mirrors the rule the profile module already uses, and for the same reason: absent and
 * null cannot both mean "clear", so the fields that have an empty state get one and the fields
 * that do not, do not.
 *
 * <p>Deliberately absent, as on create: no {@code userId}, no {@code isDefault}, no
 * {@code createdBy}. The owner cannot be reassigned through this endpoint - the schema forbids
 * changing a row's scope at all, because it would move ownership of every record pointing at it.
 */
@Schema(description = "The category fields to change. Omit a field to leave it as it is (UC-06).")
public record UpdateCategoryRequest(

        @Schema(description = "New display name. Omit to leave it unchanged; it cannot be blank.",
                example = "Coffee & Snacks", nullable = true)
        // The same trimmed-length pattern as on create. Null passes, so omitting the field is not
        // an error; "" and "   " do not, because there is no such thing as a nameless category.
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
        // The optional group means the empty string is accepted - that is how the field is
        // cleared - while any non-empty value must still be a full hex colour.
        @Pattern(regexp = "^\\s*(#[0-9A-Fa-f]{6})?\\s*$",
                message = "Colour must be a hex value such as #F59E0B.")
        String color,

        @Schema(description = "Note. Send an empty string to clear it.",
                example = "Boba and study snacks", nullable = true)
        // (?s) so a multi-line note is accepted, matching what the message says. See the same
        // pattern on CreateCategoryRequest.
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
