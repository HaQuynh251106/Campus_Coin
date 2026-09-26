package com.campuscoin.category.dto;

import com.campuscoin.category.entity.CategoryType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One category as the client sees it (UC-06).
 *
 * <p>Returned for every category in the list, whether it is the student's own or a shared default;
 * {@code isDefault} is what tells them apart. There is no separate response type for the two
 * because they are the same thing to a picker - a name, a type, an icon and a colour - and two
 * types would force the client to merge two lists to build one dropdown.
 *
 * <p>The field names follow the shape the Angular frontend already declares in
 * {@code category.model.ts} ({@code name}, {@code type}, {@code icon}, {@code color},
 * {@code isDefault}, {@code description}) so that wiring the service to this API is a change of
 * transport rather than a change of every template. Two differences are deliberate and are
 * recorded in {@code docs/api/categories.md} for the frontend developer:
 *
 * <ul>
 *   <li>{@code id} is the real numeric primary key, not the mock's string. A client that stores it
 *       should treat it as a number; a URL path takes either.</li>
 *   <li>{@code isActive} and {@code sortOrder} are included, which the mock did not carry. Both
 *       are columns of the real table and both are needed: {@code isActive} is how BR-07's
 *       "retire instead of delete" is visible, and {@code sortOrder} is the order the database
 *       sorts by. A mock that omitted them would have to be revisited the moment retirement was
 *       implemented.</li>
 * </ul>
 *
 * <p>Three fields are omitted when they are null rather than serialised as {@code null}:
 * {@code icon}, {@code color} and {@code description} are all nullable columns, and the frontend
 * model already marks {@code description} optional. Omitting them means an absent field and a
 * null field look the same to the client, which is the same convention the profile module uses
 * for writable fields.
 *
 * <p>Deliberately absent: {@code userId} and anything about who created the row. A default
 * category belongs to no one, and reporting the owner of a personal one would be a field the
 * client has no use for and must not be tempted to send back.
 */
@Schema(description = "A category the student can use (UC-06).")
public record CategoryResponse(

        @Schema(description = "Identifier, as the database assigned it. The value in this example "
                + "is an illustration of the type, not a row that exists: ids are assigned by the "
                + "database, so a client must always take them from a response rather than assume "
                + "one. `1` is used here because the first row seeded by db/05_seed.sql always has "
                + "it.", example = "1")
        Long id,

        @Schema(description = "Display name. Unique among the student's own categories of the "
                + "same type, and never the same as a default category's.",
                example = "Coffee & Snacks")
        String name,

        @Schema(description = "Whether records filed here are income or expense. This is the "
                + "single source of truth for the type of a transaction (BR-05).", example = "EXPENSE")
        CategoryType type,

        @Schema(description = "Icon name for the client to resolve. Omitted when not set.",
                example = "coffee", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String icon,

        @Schema(description = "Hex colour, exactly #RRGGBB. Omitted when not set.",
                example = "#F59E0B", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String color,

        @Schema(description = "True for one of the shared default categories, which the student "
                + "cannot edit or delete (BR-06).", example = "false")
        Boolean isDefault,

        @Schema(description = "False when the category has been retired (BR-07). A retired "
                + "category keeps its name and its history but should not be offered for new "
                + "records.", example = "true")
        Boolean isActive,

        @Schema(description = "Display order.", example = "0")
        Integer sortOrder,

        @Schema(description = "Optional free-text note. Omitted when not set.",
                example = "Boba and study snacks", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String description) {
}
