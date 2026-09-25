package com.campuscoin.tips.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.tips.dto.TipListResponse;
import com.campuscoin.tips.dto.TipMonthsResponse;
import com.campuscoin.tips.dto.TipResponse;
import com.campuscoin.tips.dto.UpdateTipStateRequest;
import com.campuscoin.tips.service.TipService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * A student's saving tips, and pinning or dismissing one: UC-18.
 *
 * <p>Four endpoints: list a month's tips, list the months that have tips, generate this month's, and
 * change one tip's state. There is no create, no edit and no delete: a tip is written by
 * {@code sp_generate_tips}, which applies the six rules and renders the text through a template, and
 * the only part of it a student owns is whether they kept it. Offering an edit would let a client
 * write advice the rules never produced; offering a delete would remove the row that
 * {@code uk_tip_dedupe} uses to keep a dismissed tip from coming back.
 *
 * <p><b>Generating is its own endpoint rather than a side effect of reading.</b> Listing a month is a
 * read and generates nothing, so opening the tips screen cannot alter the list being shown; the
 * student asks for a refresh explicitly. It is also what makes the advice current on demand instead
 * of only when the scheduled run next fires.
 *
 * <p><b>Changing state is a {@code POST} to a {@code /state} sub-path, not a {@code PATCH} of a
 * field.</b> The transition sets two columns together - the state and the timestamp
 * {@code ck_tip_state} pairs with it - and which pair depends on the state; a client that could set
 * {@code pinnedAt} directly could produce a pinned tip with no pinned time, which the schema refuses
 * and which has no meaning. The same shape the notification module uses for marking a message read,
 * and for the same reason: the server decides what a state change writes.
 *
 * <p>Every method reads the caller with {@code @AuthenticationPrincipal}, so identity comes from the
 * verified bearer token. No endpoint accepts a user id, so there is no way to reach another student's
 * tips - which matters here because a tip's title and body are readable prose about one student's
 * spending, naming their categories and the amounts they spent on them.
 */
@RestController
@RequestMapping("/api/v1/tips")
@Tag(name = "Saving Tips",
        description = "The saving tips generated for the signed-in student, and pinning or "
                + "dismissing one (UC-18).")
@SecurityRequirement(name = "bearerAuth")
public class TipController {

    private final TipService tipService;

    public TipController(TipService tipService) {
        this.tipService = tipService;
    }

    @GetMapping
    @Operation(
            summary = "List my saving tips for a month",
            description = """
                    Returns the caller's tips for one month, already ranked: pinned tips first, then \
                    by how much following the advice could save (BR-14).

                    `month` is optional. Left out, it means the current month as the server judges it, \
                    and the response says which month it answered. It is written `yyyy-MM`.

                    Reading does **not** generate. A month whose tips were never produced returns an \
                    empty `tips` array rather than creating them - the request that generates is \
                    separate, so a student looking at their screen cannot change what is on it. An \
                    empty array is a real answer: the month exists and holds no advice for this \
                    student, which is different from a month that does not exist.

                    A dismissed tip is not returned. That is BR-14's rule applied where the tips are \
                    read, so dismissing one removes it from this list for good rather than until the \
                    next generation.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The month's tips, ranked.",
                    content = @Content(schema = @Schema(implementation = TipListResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "`month` is not a valid month.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TipListResponse listTips(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) String month) {
        return tipService.listTips(principal, month);
    }

    @GetMapping("/months")
    @Operation(
            summary = "List the months that have tips",
            description = """
                    Returns the months the caller has tips to show for, newest first, as `yyyy-MM`.

                    This exists so a month picker offers only months that would return something. \
                    Building the list from the student's transactions instead would offer months \
                    whose tips were never generated, and opening one would show an empty screen behind \
                    a menu entry.

                    A month whose tips have all been dismissed is not listed: it would return an empty \
                    array if asked for.

                    The list can be empty. A student whose tips have never been generated has no \
                    months at all, and the current month is not special-cased into the list - it \
                    appears only if it actually holds a tip.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The months with at least one tip.",
                    content = @Content(schema = @Schema(implementation = TipMonthsResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TipMonthsResponse listMonths(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return tipService.listMonths(principal);
    }

    @PostMapping("/generate")
    @Operation(
            summary = "Generate my tips for this month",
            description = """
                    Runs the tip generator for the caller and the current month, then returns the \
                    resulting list - so a client gets the new advice without a second request.

                    This is what the scheduled run does, on demand. It exists because the schedule \
                    runs on a timer: a student who records a large purchase and wants the advice it \
                    should produce would otherwise wait for the next run.

                    **Safe to call repeatedly.** A tip that already exists for its month and rule is \
                    skipped, so a second call produces nothing new and, in particular, cannot bring \
                    back a tip the student dismissed or move one they pinned. Pressing refresh cannot \
                    undo the student's own choices.

                    The month is not a parameter: the tip generator runs for the current month only, \
                    so a client cannot pull advice about a month whose tips were never meant to be \
                    shown.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The current month's tips, after generating.",
                    content = @Content(schema = @Schema(implementation = TipListResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TipListResponse generateTips(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return tipService.generateTips(principal);
    }

    @PostMapping("/{id}/state")
    @Operation(
            summary = "Pin, dismiss or clear one of my tips",
            description = """
                    Moves one of the caller's tips into the requested state and returns it.

                    `PINNED` shows the tip first in every list and keeps it there. `DISMISSED` removes \
                    it from the list for good. `NEW` clears either, which is how a tip is unpinned.

                    **Asking for the state a tip already holds is not an error.** The tip comes back \
                    unchanged, and a retry or a second device settles on the state that already \
                    holds - the treatment a second mark-read gets.

                    **Dismissal is one-way.** A dismissed tip cannot be moved back to `NEW` or \
                    `PINNED`; that request is refused with `400`, and the field error says why. \
                    Everything else is accepted, including un-pinning.

                    A tip that does not exist and a tip belonging to another student answer the same \
                    `404`, so this endpoint cannot be used to discover which tip identifiers exist.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The tip, in its new state.",
                    content = @Content(schema = @Schema(implementation = TipResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "`state` is missing, or the tip was dismissed and cannot be restored.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No such tip of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TipResponse changeState(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTipStateRequest request) {
        return tipService.changeState(principal, id, request);
    }
}
