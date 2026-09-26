package com.campuscoin.recent.dto;

import com.campuscoin.recent.entity.RecentAction;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/v1/recent-activity} (UC-26): record that the student has just looked at,
 * or just changed, one of their own transactions.
 *
 * <p><b>Two fields an owner and no third.</b> There is deliberately no {@code userId}: the owner is
 * the bearer token's subject, and an endpoint that accepted one would let a client record activity
 * against another student's transaction - which is exactly what {@code sp_touch_recent_activity}
 * refuses with {@code SIGNAL SQLSTATE '45000'} when the row's owner and the caller disagree. The
 * column {@code user_id} is not a field of this request for the same reason it is not a field of
 * {@code CreateTransactionRequest}: ownership is never something a client states about itself.
 *
 * <p><b>{@code occurredAt} is not a field either, and that is a decision rather than an omission.</b>
 * UC-26's postcondition is "the last few things I looked at", which is a claim about the order the
 * student acted in. A client-supplied timestamp would let a request in the future pin an entry above
 * everything the student does afterwards, and a clock that disagrees with the server would reorder the
 * list for reasons that have nothing to do with what happened. The procedure writes {@code NOW()} on
 * the database's clock - the same clock the rest of the row's timestamps come from - so the order is
 * the order the requests arrived in.
 *
 * <p><b>{@code action} is required.</b> UC-26 B1 is "the student views a transaction, or edits one",
 * so which of the two happened is part of the request, and defaulting it would mean the server
 * guessing. The type is the same {@link RecentAction} the column's {@code ENUM} mirrors, and
 * {@code spring.jackson.deserialization.fail-on-numbers-for-enums} keeps a client from sending the
 * ordinal in its place. A value outside the two is a JSON parse failure reported as a field error.
 *
 * <p>The pair is idempotent by design: {@code uk_recent} is
 * {@code (user_id, transaction_id, action)}, and the procedure upserts onto it with
 * {@code ON DUPLICATE KEY UPDATE occurred_at = NOW()}. Recording the same view twice does not create a
 * second entry - it moves the one entry up, which is what "recently viewed" should do.
 */
@Schema(description = "A transaction the student just viewed or edited (UC-26).")
public record RecordRecentActivityRequest(

        @Schema(description = "The transaction's `id`. It must be one of the caller's own; BR-02 is "
                + "enforced by the database, and another student's transaction is refused with the "
                + "same `404` a transaction that does not exist gets.",
                example = "31", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Provide the id of the transaction.")
        Long transactionId,

        @Schema(description = "`VIEWED` when the student opened the record to read it, `EDITED` when "
                + "they changed it.", example = "VIEWED", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Say what happened: VIEWED or EDITED.")
        RecentAction action) {
}
