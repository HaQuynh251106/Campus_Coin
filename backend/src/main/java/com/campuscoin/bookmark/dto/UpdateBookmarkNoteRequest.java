package com.campuscoin.bookmark.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

@Schema(description = "The bookmark note to set, or an empty string to clear it (UC-19 B2).")
public record UpdateBookmarkNoteRequest(

        @Schema(description = "The new note. Omit it, or send null, to leave the note unchanged. "
                + "Send an empty string to remove the note.",
                example = "Check whether this still applies in October", nullable = true)
        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Note must be at most 255 characters.")
        String note) {
}
