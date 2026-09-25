package com.campuscoin.bookmark.dto;

import com.campuscoin.bookmark.entity.BookmarkItemType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code POST /api/v1/bookmarks} (UC-19 B1, B2): save an item for later, with an optional
 * short note.
 *
 * <p><b>{@code itemType} is required rather than assumed.</b> UC-19 B1 is "the student marks a tip
 * <em>or</em> an insight", so which kind of item is being saved is part of the request, and a server
 * that defaulted it would be guessing what the client meant. It is typed by the same
 * {@link BookmarkItemType} the column uses, so the schema advertises the two values MySQL holds and
 * there is no second vocabulary to drift.
 *
 * <p><b>{@code INSIGHT} is a valid value that this build refuses, and it is refused where the reason
 * can be explained.</b> Insights are UC-17 - module 12, locked pending the project owner's approval -
 * and {@code insights} has no read path anywhere in the repository. Narrowing the enum here would
 * turn that request into a JSON parsing failure whose message says the value is invalid, which is
 * both untrue and unhelpful; the service refuses it instead with a field error naming the scope
 * decision. See {@code BookmarkService#create}.
 *
 * <p><b>{@code itemId} rather than {@code tipId}.</b> The identifier means "the row of the kind named
 * by {@code itemType}", which is exactly how the schema's CHECK treats the pair: {@code item_type}
 * selects which of {@code tip_id} / {@code insight_id} must be set. Naming the field after one of the
 * two branches would say this endpoint is about tips while accepting {@code INSIGHT}, and a client
 * written against it would be told the wrong thing about what it may send.
 *
 * <p><b>{@code note} is optional and holds plaintext at this boundary.</b> UC-19 B2 proposes it, so a
 * bookmark without one is the ordinary case and {@code null} is accepted. The value is trimmed, an
 * empty one becomes no note at all, and what reaches the database is an AES-256-GCM envelope the
 * service produces - the request is the last place the words exist as typed, and the response is the
 * next. The 255-character bound is the plaintext limit, the same as
 * {@code transactions.description} and {@code recurring_rules.description}: the column is wider
 * (2048) only because an envelope is longer than what it protects.
 */
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
        // (?s) so a note with a line break is accepted, matching what the message says. The same
        // whitespace-tolerant trimmed-length pattern the other encrypted free-text fields use.
        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Note must be at most 255 characters.")
        String note) {
}
