package com.campuscoin.recent.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The "recently viewed" list (UC-26).
 *
 * <p><b>Why the limit is echoed back.</b> UC-26 says "the last few", not a fixed number, so the caller
 * chooses the size and the response says which one it got - the same reason {@code TipListResponse}
 * echoes the month the server chose when the client did not name one. A client that asked for five and
 * would have rendered ten cannot tell from the array alone whether the student has only viewed five
 * things or whether the server capped the answer.
 *
 * <p><b>{@code entries} is present and empty when nothing has been viewed.</b> A newly registered
 * student has looked at nothing, and an empty array is the honest answer: the endpoint does not answer
 * {@code 404}, because there is no missing resource - the list exists and it is empty. This is the
 * same convention {@code TipListResponse} and {@code BookmarkController}'s bare array use.
 *
 * <p>A wrapper rather than a bare array, unlike the bookmarks list. The bookmarks list has nothing to
 * say beyond its contents; this one has to report a bound the caller supplied, and a bare array has
 * nowhere to put it.
 */
@Schema(description = "The transactions the student most recently opened or changed (UC-26).")
public record RecentActivityListResponse(

        @Schema(description = "How many entries the server was asked for and applied. Echoed so a "
                + "client can tell a list that ends because the student has seen little apart from "
                + "one that was capped.", example = "10")
        int limit,

        @Schema(description = "The entries, most recent first. Empty when the student has not yet "
                + "opened or changed anything.")
        List<RecentActivityResponse> entries) {
}
