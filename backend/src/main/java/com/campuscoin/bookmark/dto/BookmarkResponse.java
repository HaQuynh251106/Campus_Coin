package com.campuscoin.bookmark.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.campuscoin.bookmark.entity.BookmarkItemType;
import com.campuscoin.tips.entity.TipState;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One saved item as a bookmarks list shows it (UC-19 B4).
 *
 * <p><b>The tip's own words travel with the bookmark.</b> UC-19's postcondition is that a marked item
 * can be looked at again later, so this response carries the tip's title, body and estimated saving
 * rather than an identifier the client would have to resolve separately. A saved tip is the advice
 * itself; making a client fetch it back per row would be a second request for something this one
 * already reaches.
 *
 * <p><b>{@code tipState} and {@code tipMonth} are published, and they are not the bookmark's.</b> They
 * describe the tip that was saved, and both are needed to place it: the month is what the tips screen
 * is scoped by, so a client links the row back to the month it belongs to, and the state says whether
 * the advice is pinned, still new, or was dismissed after it was saved. A dismissed tip is
 * deliberately <em>not</em> filtered out here - the student chose to keep it, and UC-19 B4 offers
 * un-marking as the way to let it go.
 *
 * <p><b>{@code note} is decrypted before it reaches this type.</b> The student's own words are the one
 * field the bookmark adds, and they are read back from an AES-256-GCM envelope by
 * {@code BookmarkMapper}; this record only ever holds plaintext on the way out. It is omitted entirely
 * when there is no note, so a client can tell "no note" from "an empty note" without a second field.
 *
 * <p>{@code userId} is not carried. Ownership is what the query already applied, and a client has no
 * use for it - the same reasoning every other response in this API records.
 */
@Schema(description = "An item the student saved to look at again, with the tip it points at (UC-19).")
public record BookmarkResponse(

        @Schema(description = "Identifier of the bookmark, used by the note and un-mark actions.",
                example = "4")
        Long id,

        @Schema(description = "`TIP` for a saving tip. `INSIGHT` belongs to UC-17, which is not "
                + "enabled in this build, so this field holds only `TIP` in practice.",
                example = "TIP")
        BookmarkItemType itemType,

        @Schema(description = "The tips endpoint's `id` for the saved tip.", example = "12")
        Long tipId,

        @Schema(description = "The saved tip's headline.", example = "Entertainment spending is up 127.3%")
        String tipTitle,

        @Schema(description = "The saved advice itself.", example = "This month you spent 25.00 on "
                + "Entertainment, against your usual 11.00. A rise of 127.3% is worth a look - try "
                + "setting a weekly cap for this category.")
        String tipBody,

        @Schema(description = "What following the advice is estimated to save, in the account's "
                + "currency. `0.00` for advice with no figure attached.", example = "11.20")
        BigDecimal tipPotentialSaving,

        @Schema(description = "The saved tip's state: `NEW`, `PINNED` or `DISMISSED`. A tip "
                + "dismissed on the tips screen stays in this list until the bookmark is removed, "
                + "which is what distinguishes keeping an item from displaying it (VĐ-03).",
                example = "NEW")
        TipState tipState,

        @Schema(description = "The month the saved tip is about, as `yyyy-MM`.", example = "2026-09")
        String tipMonth,

        @Schema(description = "The caller's own note about the saved item. Omitted when there is "
                + "none.", example = "Try this next month - the boba runs are the whole problem",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String note,

        @Schema(description = "When the item was saved, in the application's zone. The list is "
                + "ordered by this, newest first.", example = "2026-09-24T21:15:30")
        LocalDateTime createdAt) {
}
