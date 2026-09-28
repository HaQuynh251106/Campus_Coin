package com.campuscoin.imports.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "The category to file one previewed row under (UC-11 B6).")
public record SetImportRowCategoryRequest(

        @Schema(description = "A category of your own or a shared default one. It decides the "
                + "record's type as well (BR-05), which is how a wrong type in the file is corrected. "
                + "It must still be usable when the import is committed.",
                example = "1")
        @NotNull(message = "Category is required.")
        Long categoryId) {
}
