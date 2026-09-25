package com.campuscoin.admin.dto;

import com.campuscoin.category.entity.CategoryType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code PATCH /api/v1/admin/categories/{id}} (UC-20): change some fields of a default
 * category.
 *
 * <p>Absent and {@code null} both mean "leave this field as it is", the same convention
 * {@code UpdateCategoryRequest} uses, so a client sends only what it edited.
 *
 * <p><b>This endpoint cannot clear a field, and that is a property of the database surface rather
 * than a choice made here.</b> {@code sp_admin_upsert_default_category} writes each column as
 * {@code IFNULL(p_value, column)}, so the only two things a parameter can do are replace the value
 * or leave it alone - there is no third input that produces {@code NULL}. The student-facing
 * {@code PATCH} can offer "send an empty string to clear" because it writes through an entity and
 * can name the column directly; this one cannot, and the difference matters most for
 * {@code color}, which is {@code CHAR(7)}: an empty string would be stored as seven spaces rather
 * than as no colour, which is a value no client could make sense of. The patterns below therefore
 * reject an empty string instead of accepting it and storing something the caller did not ask for.
 * Clearing is recorded in {@code docs/api/administration.md} as unavailable rather than silently
 * approximated.
 *
 * <p><b>{@code name} and {@code type} do not need a clear state</b>, and a blank there is a
 * validation error for the reason it is on the student-facing endpoint: a category with no name
 * cannot be picked from a list, and {@code categories.type} is what decides whether a record filed
 * under it is income or expense (BR-05).
 *
 * <p>{@code type} is accepted here although changing it is refused by the database once any
 * transaction, budget or recurring rule uses the category - {@code trg_categories_before_update}
 * enforces that, and this module translates its refusal rather than restating the rule. A default
 * category nothing references may legitimately change type.
 */
@Schema(description = "The default category fields to change. Omit a field to leave it as it is "
        + "(UC-20).")
public record UpdateDefaultCategoryRequest(

        @Schema(description = "New display name. Omit to leave it unchanged; it cannot be blank.",
                example = "Campus Cafe", nullable = true)
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
        // \S is required: an empty string is refused, because the procedure cannot store NULL.
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
