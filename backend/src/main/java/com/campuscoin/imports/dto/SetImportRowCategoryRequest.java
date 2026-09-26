package com.campuscoin.imports.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/v1/imports/{batchId}/rows/{rowId}} (UC-11 B6): choose the category for one
 * row of the preview.
 *
 * <p><b>This is UC-11 B5/B6 as an endpoint, and the reason it matters is what the commit does with it.</b>
 * {@code sp_apply_csv_batch} treats a set {@code resolved_category_id} as the student's authoritative
 * choice: it is used as-is and the file's category name is never re-derived. Without this endpoint the
 * student could see that a row was misfiled and have no way to say so, and the procedure's Case A - and
 * the whole asymmetry between Case A and Case B - would exist for nothing.
 *
 * <p><b>Only the category, because the category is the only part of a row the student corrects.</b> The
 * date, the amount, the description and the type are the file's own content: a student who wanted a
 * different one would fix it in the file, where the correction is visible in the source they keep. The
 * category is different because it is a choice about how the record is <em>classified</em>, which is
 * this application's job rather than their spreadsheet's - and it is the correction UC-08 learns from.
 *
 * <p><b>The type travels with the category and is never sent separately.</b> BR-05 makes a record's type
 * its category's type, so choosing a category is also how the student corrects a type the file got
 * wrong - exactly as the commit procedure's own comment records. A separate {@code type} field would
 * allow a request whose two fields disagree, and there is no rule for which one would win.
 *
 * <p><b>There is no way to <em>unset</em> a choice - no null, and no "clear" spelling.</b> That is
 * deliberate and is the one place this request departs from the absent/null/empty convention
 * {@code UpdateCategoryRequest} and {@code UpdateBookmarkNoteRequest} share. Those update a value that
 * already exists and can be returned to nothing. Here the column starts null and the student's act is
 * the only thing that fills it, so "I want to undo my choice" is not a state the preview has: the row
 * would have to decide what the file's name meant after the fact, and the procedure has no path for a
 * cleared choice. A student who wants the file's own category back leaves the row alone.
 */
@Schema(description = "The category to file one previewed row under (UC-11 B6).")
public record SetImportRowCategoryRequest(

        @Schema(description = "A category of your own or a shared default one. It decides the "
                + "record's type as well (BR-05), which is how a wrong type in the file is corrected. "
                + "It must still be usable when the import is committed.",
                example = "1")
        @NotNull(message = "Category is required.")
        Long categoryId) {
}
