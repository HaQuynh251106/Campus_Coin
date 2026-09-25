package com.campuscoin.category.dto;

import com.campuscoin.category.entity.CategoryType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code POST /api/v1/categories} (UC-06): create one personal category.
 *
 * <p>{@code name} and {@code type} are required; everything else has a sensible absence. The three
 * optional text fields accept an empty string and store null, so a form that submits its blank
 * inputs unchanged is not rejected - only a value that is genuinely too long or malformed is.
 *
 * <p>Every text limit is applied to the value <em>after trimming</em>, because the service trims
 * before storing. A plain {@code @Size} would measure the raw input and could reject padding that
 * lands inside the column, or accept a value that trims outside it. The patterns below measure the
 * trimmed length instead, which is exactly what the column receives.
 *
 * <p>Deliberately absent: {@code userId}, {@code isDefault} and {@code createdBy}. The owner is the
 * account in the bearer token - a client-supplied owner would be a direct BR-02 bypass - and
 * whether a row is a personal or a default category is decided by the schema's {@code user_id},
 * never by a field. Sending any of them changes nothing, which is asserted by a test.
 */
@Schema(description = "A personal category to create (UC-06).")
public record CreateCategoryRequest(

        @Schema(description = "Display name. Cannot be blank, and must not repeat one of your "
                + "existing categories of the same type or any default category's name.",
                example = "Campus Cafe")
        @NotNull(message = "Name is required.")
        // Measures the trimmed length, which is what is stored in name VARCHAR(80): at least one
        // non-space character, at most 80 characters between the first and the end. The trailing
        // \s* lets a padded value through as long as it trims inside the column.
        @Pattern(regexp = "^\\s*\\S.{0,79}\\s*$",
                message = "Name must be between 1 and 80 characters and cannot be blank.")
        String name,

        @Schema(description = "Whether this category records income or expense (BR-05). "
                + "It cannot be changed later if any record already uses the category.",
                example = "EXPENSE")
        @NotNull(message = "Type is required.")
        CategoryType type,

        @Schema(description = "Icon name for the client to resolve. Optional; an empty string "
                + "stores no icon.", example = "coffee", nullable = true)
        @Pattern(regexp = "^\\s*.{0,50}\\s*$",
                message = "Icon must be at most 50 characters.")
        String icon,

        @Schema(description = "Hex colour, exactly #RRGGBB. Optional; an empty string stores no "
                + "colour.", example = "#F59E0B", nullable = true)
        // The whole value is the pattern, so "#F59E0B" passes and "red" or "#FFF" does not. The
        // column is CHAR(7), so an unchecked value would either be rejected by MySQL or silently
        // padded - neither of which the caller could act on.
        @Pattern(regexp = "^\\s*(#[0-9A-Fa-f]{6})?\\s*$",
                message = "Colour must be a hex value such as #F59E0B.")
        String color,

        @Schema(description = "Optional note. An empty string stores no description.",
                example = "Boba and study snacks", nullable = true)
        // (?s) lets `.` match a newline, so a multi-line note is accepted. Without it the pattern
        // would reject one while the message claimed the only rule was the length, which is a
        // discrepancy a caller cannot act on. The column is VARCHAR(255), which holds a newline
        // perfectly well; `name` and `icon` keep the single-line behaviour, which is correct for
        // those two and does not need saying.
        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Description must be at most 255 characters.")
        String description,

        @Schema(description = "Display order within the type. The database default is 0.",
                example = "0", nullable = true)
        // The column is SMALLINT, so the bound mirrors it rather than trusting MySQL to refuse an
        // out-of-range value with a message the caller cannot use.
        @Min(value = 0, message = "Sort order cannot be negative.")
        @Max(value = 32767, message = "Sort order must be at most 32767.")
        Integer sortOrder,

        @Schema(description = "False retires the category at once (BR-07). The database default "
                + "is true.", example = "true", nullable = true)
        Boolean isActive) {
}
