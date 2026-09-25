package com.campuscoin.bookmark.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code PATCH /api/v1/bookmarks/{id}} (UC-19 B2): set or clear the note on one of the
 * caller's own bookmarks.
 *
 * <p><b>Only the note, because the note is the only part of a bookmark UC-19 lets a student edit.</b>
 * Which item is saved and when it was saved are what the bookmark <em>is</em>; changing the target
 * would not be editing this bookmark but saving a different one, and the schema refuses it outright -
 * {@code trg_bookmarks_before_update} exists to make a bookmark's target unchangeable. Un-marking is
 * B4's "remove it when no longer needed", which is {@code DELETE}, not a field change.
 *
 * <p><b>The same absent / null / empty-string rule the category update uses.</b> An absent field and
 * an explicit {@code null} both mean "leave the note as it is", because PHP, JavaScript and Java all
 * have to be able to express "I did not touch this" and a JSON body has only those two spellings for
 * it. Clearing needs its own spelling, and the empty string is it: sending {@code ""} (or whitespace)
 * stores no note. That mirrors {@code UpdateCategoryRequest} exactly, so a client that has learned one
 * convention has learned both.
 *
 * <p>{@code note} holds plaintext here. The service trims it, encrypts it, and what the database
 * receives is a Base64 AES-256-GCM envelope - see {@code docs/SECURITY.md} §12. The 255-character
 * bound is the plaintext limit the column's ciphertext width was sized for.
 */
@Schema(description = "The bookmark note to set, or an empty string to clear it (UC-19 B2).")
public record UpdateBookmarkNoteRequest(

        @Schema(description = "The new note. Omit it, or send null, to leave the note unchanged. "
                + "Send an empty string to remove the note.",
                example = "Check whether this still applies in October", nullable = true)
        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Note must be at most 255 characters.")
        String note) {
}
