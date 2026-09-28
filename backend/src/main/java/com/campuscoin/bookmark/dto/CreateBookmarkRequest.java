package com.campuscoin.bookmark.dto;

import com.campuscoin.bookmark.entity.BookmarkItemType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "The item to save and, optionally, a short note about it (UC-19).")
public record CreateBookmarkRequest(

        @Schema(description = "What kind of item is being saved. `TIP` is a saving tip from the "
                + "tips screen (UC-18). `INSIGHT` belongs to UC-17, which is not enabled in this "
                + "build, and is refused with a field error saying so.",
                example = "TIP", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Choose what is being saved: TIP or INSIGHT.")
        BookmarkItemType itemType,

        @Schema(description = "Identifier of the item to save, read together with `itemType`. For "
                + "`TIP` this is the `id` the tips endpoint returns. The item must be the caller's "
                + "own - BR-02.",
                example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Provide the id of the item to save.")
        Long itemId,

        @Schema(description = "An optional short note. Omit it for a bookmark with no note.",
                example = "Try this next month - the boba runs are the whole problem", nullable = true)

        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Note must be at most 255 characters.")
        String note) {
}
