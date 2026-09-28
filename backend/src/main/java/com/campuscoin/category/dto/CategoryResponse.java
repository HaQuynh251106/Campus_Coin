package com.campuscoin.category.dto;

import com.campuscoin.category.entity.CategoryType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

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
