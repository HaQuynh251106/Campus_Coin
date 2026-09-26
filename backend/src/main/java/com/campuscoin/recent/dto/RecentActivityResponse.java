package com.campuscoin.recent.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.recent.entity.RecentAction;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry of the "recently viewed" list (UC-26).
 *
 * <p><b>Enough to render the row without a second request, and no more.</b> UC-26's postcondition is
 * that a student can go back to something they were looking at, so an entry that named only a
 * transaction id would make the client fetch each row back one at a time - which is the request the
 * list exists to answer. So the category, the amount and the date travel with it, and the client can
 * draw the line as it draws every other transaction line in the application.
 *
 * <p><b>{@code description} is decrypted before it reaches this type.</b> As stored, the column is an
 * AES-256-GCM envelope; {@code RecentActivityMapper} reads it back with the tolerant
 * {@code decryptStored}, so a row written before encryption was enabled still reads rather than
 * failing the whole list. This record only ever holds plaintext. It is omitted entirely when there is
 * no description, which lets a client tell "no description" from "an empty one" - the same convention
 * {@code TransactionResponse} and {@code BookmarkResponse} use for their nullable text.
 *
 * <p><b>{@code action} is what separates two entries for one transaction.</b> {@code uk_recent} is
 * {@code (user_id, transaction_id, action)}, so a record that was read and later changed has two rows
 * on this list and they are different facts - "you looked at this" and "you changed this". Dropping
 * the field would leave two entries with the same id, amount and date and no way to tell them apart.
 *
 * <p><b>{@code amount} is published in the clear, and that is deliberate.</b> It is the student's own
 * figure, read from their own row, on an endpoint only they can reach; UC-13, UC-15 and UC-12 all
 * serve the same kind of value for the same reason. The application-level field encryption covers the
 * free-text columns, not amounts - see OB-013 and {@code docs/SECURITY.md} §12.
 *
 * <p>{@code userId} is not carried. Ownership is what the query already applied.
 */
@Schema(description = "A transaction the student recently opened or changed (UC-26).")
public record RecentActivityResponse(

        @Schema(description = "The transaction's `id`. It is the identifier the transactions "
                + "endpoints use, so the entry can be opened directly.", example = "31")
        Long transactionId,

        @Schema(description = "What the student did: `VIEWED` opened it to read, `EDITED` changed it. "
                + "One transaction can appear twice, once per action.", example = "VIEWED")
        RecentAction action,

        @Schema(description = "When the action happened, in the application's zone. The list is "
                + "ordered by this, most recent first.", example = "2026-09-25T19:04:11")
        LocalDateTime occurredAt,

        @Schema(description = "Identifier of the category the transaction is filed under (BR-05).",
                example = "4")
        Long categoryId,

        @Schema(description = "`EXPENSE` or `INCOME`, read from the category rather than sent with "
                + "the transaction.", example = "EXPENSE")
        CategoryType categoryType,

        @Schema(description = "The transaction's amount, in the account's currency.", example = "25.00")
        BigDecimal amount,

        @Schema(description = "The student's own description of the record, decrypted. Omitted when "
                + "there is none.", example = "Campus cafe - lunch with Linh", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String description,

        @Schema(description = "The date the transaction was recorded against, which is the date the "
                + "student entered rather than the date of this action.", example = "2026-09-24")
        LocalDate txnDate) {
}
