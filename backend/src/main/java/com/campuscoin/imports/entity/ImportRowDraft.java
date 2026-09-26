package com.campuscoin.imports.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.campuscoin.category.entity.CategoryType;

/**
 * One row as the preview decided it, before it is written (UC-11).
 *
 * <p><b>Why this exists as a separate shape from {@link ImportRow}.</b> The preview computes a verdict
 * for every line of the file and then writes the whole batch in one statement. The verdict is reached
 * before the row has an id, a {@code resolved_category_id} or a {@code transaction_id} - all three are
 * things only a later step can fill in - so a draft carrying them would carry three fields that are
 * always null at the moment it exists. The mapper that writes it to the database fills
 * {@code resolved_category_id} from the student's override, which is not yet known here.
 *
 * <p>{@code aiSuggestedCategoryId} is the exception: it is decided during the preview, because the
 * suggestion is part of what the student is shown. It is written with the row so the preview and the
 * stored row cannot disagree about what was proposed.
 *
 * <p>{@code rawData} is the original line as a JSON object, so a client can show the row as it arrived
 * in the file. It is built by the parser and never re-derived from the parsed fields.
 *
 * <p>{@code errorMessage} is non-null exactly when {@code rowStatus} is
 * {@link ImportRowStatus#ERROR}, and it is written in the student's terms - "Amount must be a positive
 * number", not a parser stack trace. It is what makes UC-11's "invalid rows must be identifiable" true
 * on the screen rather than only in a log.
 */
public record ImportRowDraft(
        int csvRowNo,
        String rawData,
        LocalDate parsedDate,
        BigDecimal parsedAmount,
        CategoryType parsedType,
        String parsedDescription,
        String parsedCategoryName,
        Long aiSuggestedCategoryId,
        ImportRowStatus rowStatus,
        String errorMessage) {
}
