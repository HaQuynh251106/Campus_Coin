package com.campuscoin.tips.dto;

import com.campuscoin.tips.entity.TipState;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * The state to move a tip into (UC-18).
 *
 * <p><b>One field, and it is the whole request.</b> A tip's title, body, saving and month are the
 * database's to decide, so there is nothing else a client could usefully send; a body with more
 * fields would be offering to write values the schema computes.
 *
 * <p><b>All three states are accepted, and none is refused for being "the one it already is".</b>
 * {@code PINNED} pins, {@code DISMISSED} dismisses, and {@code NEW} clears either - which is what
 * "unpin" is. Asking for the state a tip already holds changes nothing and is answered with the tip,
 * for the same reason {@code sp_mark_notification_read} treats a second mark-read as success: the end
 * state the caller wants is the one that holds.
 *
 * <p><b>Dismissal is one-way, and that is enforced where the transition is applied rather than here.</b>
 * The value {@code DISMISSED} is a valid request on any tip; what is not offered is a way <em>out</em>
 * of {@code DISMISSED}, because a tip the student has thrown away should not come back a month later
 * under a new request. See {@code TipService#changeState} - the rule is about the tip's current
 * state, which this request cannot see.
 */
@Schema(description = "The new state for a tip (UC-18).")
public record UpdateTipStateRequest(

        @Schema(description = "`PINNED` shows the tip first and keeps its place; `DISMISSED` removes "
                + "it from the list for good; `NEW` clears either, which is how a tip is unpinned.",
                example = "PINNED", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Choose a state: PINNED, DISMISSED or NEW.")
        TipState state) {
}
