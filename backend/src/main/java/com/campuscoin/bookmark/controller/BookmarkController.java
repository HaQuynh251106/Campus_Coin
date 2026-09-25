package com.campuscoin.bookmark.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.bookmark.dto.BookmarkResponse;
import com.campuscoin.bookmark.dto.CreateBookmarkRequest;
import com.campuscoin.bookmark.dto.UpdateBookmarkNoteRequest;
import com.campuscoin.bookmark.service.BookmarkService;
import com.campuscoin.common.exception.ApiError;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Saving an item to look at again, noting why, and removing it: UC-19.
 *
 * <p>Four endpoints: list what is saved, save a tip, change the note on one, and un-mark one. There is
 * no endpoint that creates an item to bookmark - a tip is produced by {@code sp_generate_tips} (UC-18)
 * and this module only points at one - and no endpoint that changes what a bookmark points at, because
 * {@code trg_bookmarks_before_update} exists partly to make that unchangeable.
 *
 * <p><b>A bookmark is not a pin (VĐ-03), and the two are reachable through different routes.</b>
 * Pinning is {@code POST /api/v1/tips/{id}/state} and it is about display order - a pinned tip leads the
 * dashboard. Bookmarking is this controller and it is about keeping: it saves the item into a list that
 * outlives the session, which is UC-19's postcondition. The same tip can be both, and neither endpoint
 * changes the other's column. There is deliberately no way to pin a tip from here and no way to save one
 * from the tips endpoint, so a client cannot confuse the two acts by using the wrong URL.
 *
 * <p><b>The insight branch of UC-19 is refused rather than served.</b> UC-19 B1 names a tip or an
 * insight, and {@code bookmarks.item_type} holds both, but insights are UC-17 - inside module 12, locked
 * pending the project owner's approval - and {@code insights} has no read path anywhere in this
 * repository. {@code POST} therefore accepts {@code itemType} and answers {@code INSIGHT} with a field
 * error naming the reason, instead of exposing a locked module's contract or pretending the branch
 * works. See {@code BookmarkService} and {@code docs/OVERNIGHT_BLOCKERS.md}.
 *
 * <p>Every method reads the caller with {@code @AuthenticationPrincipal}, so identity comes from the
 * verified bearer token. No endpoint accepts a user id, so there is no way to reach another student's
 * saved list - which matters here because the list is advice about one student's own spending together
 * with a note they wrote in their own words.
 */
@RestController
@RequestMapping("/api/v1/bookmarks")
@Tag(name = "Bookmarks",
        description = "The items a student saved to look at again, and the note on each (UC-19).")
@SecurityRequirement(name = "bearerAuth")
public class BookmarkController {

    private final BookmarkService bookmarkService;

    public BookmarkController(BookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    @GetMapping
    @Operation(
            summary = "List my saved items",
            description = """
                    Returns everything the caller has saved, newest first, each entry carrying the \
                    tip it points at - its headline, its advice, what following it could save, and \
                    the month it is about.

                    `itemType` is `TIP` for every entry in this build. The column accepts `INSIGHT` \
                    and the endpoint will report it once UC-17 is approved, but nothing can create \
                    one yet.

                    A saved tip that was later dismissed on the tips screen **is still listed**. \
                    Keeping an item and displaying it are different acts: bookmarking saves it into \
                    a list that outlives the session (VĐ-03), and a bookmark is removed only by the \
                    student removing it. `tipState` says which state the saved tip is in.

                    An empty array is a real answer: a student who has saved nothing has an empty \
                    list, which is different from the list not existing.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's saved items, newest first."),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<BookmarkResponse> listBookmarks(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return bookmarkService.list(principal);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Save a tip to look at again",
            description = """
                    Saves one of the caller's own tips, optionally with a short note, and returns \
                    the saved entry.

                    `itemType` names what is being saved and `itemId` is its identifier - for a \
                    `TIP`, the `id` the tips endpoint returns. **The tip must be the caller's own**; \
                    BR-02 is enforced by the database, and a tip belonging to another student is \
                    answered with the same `404` a tip that does not exist gets, so this endpoint \
                    cannot be used to discover other students' tip identifiers.

                    `itemType` must be `TIP`. `INSIGHT` is a valid value of the column and belongs \
                    to UC-17, which is not enabled in this build, so that request is refused with \
                    `400` and a field error saying so rather than being silently treated as a tip.

                    **Saving something already saved is refused with `409` \
                    (`BOOKMARK_ALREADY_EXISTS`).** Returning the existing entry as if it had been \
                    created would tell the caller a bookmark was made when none was, and would \
                    discard the `note` this request carried. The item is already in the list; \
                    change its note with `PATCH /api/v1/bookmarks/{id}`.

                    `note` is optional and stored encrypted, so a direct read of the database does \
                    not reveal what the student wrote.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The saved item.",
                    content = @Content(schema = @Schema(implementation = BookmarkResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "`itemType` is `INSIGHT`, `itemId` is missing or not a real "
                            + "identifier, or `note` is too long.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No such tip of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The tip is already in the caller's saved list "
                            + "(BOOKMARK_ALREADY_EXISTS).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public BookmarkResponse createBookmark(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateBookmarkRequest request) {
        return bookmarkService.create(principal, request);
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Set or clear the note on a saved item",
            description = """
                    Replaces the note on one of the caller's own saved items and returns the entry.

                    Omit `note`, or send `null`, to leave the note unchanged. Send an empty string to \
                    remove it. A note that is only whitespace is stored as no note at all.

                    This is an edit rather than a remove-and-save-again on purpose. Re-creating the \
                    bookmark would give it a new saved time, move it to the top of a list ordered by \
                    when things were saved, and re-run a trigger whose job is to make a bookmark's \
                    target unchangeable.

                    The note is stored encrypted. Setting a note on a bookmark that does not exist, \
                    or that belongs to another student, answers the same `404`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The saved item, with its note.",
                    content = @Content(schema = @Schema(implementation = BookmarkResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No such bookmark of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public BookmarkResponse updateNote(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateBookmarkNoteRequest request) {
        return bookmarkService.updateNote(principal, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Un-mark a saved item",
            description = """
                    Removes one of the caller's saved items, which is how an item is let go once it \
                    is no longer needed (UC-19 B4).

                    **Idempotent.** Removing something already removed also answers `204`, because \
                    "it is not in my list" is the end state the caller asked for. A retry, or two \
                    devices acting at once, is not a failure.

                    Deleting a bookmark never touches the tip it pointed at. The saved advice stays \
                    on the tips screen; only this student's entry in their own list is removed.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed, or was already gone."),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void deleteBookmark(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id) {
        bookmarkService.delete(principal, id);
    }
}
