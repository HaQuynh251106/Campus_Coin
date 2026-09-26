package com.campuscoin.imports.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The student's imports, most recent first (UC-11).
 *
 * <p><b>Why the limit is echoed back.</b> The same reason {@code RecentActivityListResponse} and
 * {@code FlaggedTransactionListResponse} echo theirs: UC-11 names no size, so the caller chooses one
 * and the response says which it got. A client that asked for five and was sent five cannot otherwise
 * tell a short list from a capped one, and this list is one a student will scroll.
 *
 * <p><b>{@code entries} is present and empty for a student who has never imported anything.</b> That
 * is a real answer rather than a {@code 404} - there is no missing resource, the list exists and is
 * empty - and it is the convention every list endpoint in this project follows.
 *
 * <p>A wrapper rather than a bare array, unlike the bookmarks list, because this one has a bound to
 * report and a bare array has nowhere to put it.
 */
@Schema(description = "The student's CSV imports, most recent first (UC-11).")
public record ImportBatchListResponse(

        @Schema(description = "How many entries the server was asked for and applied. Echoed so a "
                + "client can tell a list that ends because the student has imported little apart "
                + "from one that was capped.", example = "20")
        int limit,

        @Schema(description = "The imports, most recent first. Empty when the student has never "
                + "imported a file.")
        List<ImportSummaryResponse> entries) {
}
