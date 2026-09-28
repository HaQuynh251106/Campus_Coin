package com.campuscoin.recent.dto;

import com.campuscoin.recent.entity.RecentAction;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

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
