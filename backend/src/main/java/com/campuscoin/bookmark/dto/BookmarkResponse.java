package com.campuscoin.bookmark.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.campuscoin.bookmark.entity.BookmarkItemType;
import com.campuscoin.tips.entity.TipState;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

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
