package com.campuscoin.recent.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.campuscoin.category.entity.CategoryType;

/**
 * One row of {@code v_user_recent_activity}: a transaction the student opened, and when (UC-26).
 *
 * <p><b>The view already excludes soft-deleted transactions</b> - {@code v_user_recent_activity} joins
 * {@code transactions} with {@code WHERE t.is_deleted = 0} - so a record the student put in the trash
 * is not on this list even though its activity row still exists, and nothing here has to say so again.
 * That is the correct reading of UC-26: the list is what the student can go back to, and a trashed
 * transaction is not that. It is also what makes restoring one bring it back onto the list without any
 * repair step, because the activity row was never deleted in the first place.
 *
 * <p><b>{@code description} is carried as it is stored, which is an AES-256-GCM envelope.</b> The name
 * says so, so a caller that publishes this field has to decrypt it first - which is what
 * {@code RecentActivityMapper} does. Carrying it decrypted here would be the more convenient shape and
 * the wrong one: it would make "a row holds ciphertext" false for this one projection, and the next
 * reader of that row would have no way to tell whether it was looking at plaintext or at a string of
 * base64.
 *
 * <p><b>{@code action} is carried because the row is keyed by it.</b> {@code uk_recent} is
 * {@code (user_id, transaction_id, action)}, so the same transaction can be on the list twice - once
 * because it was read and once because it was changed - and a client that dropped the action would be
 * showing two identical entries with no way to tell them apart.
 *
 * <p>{@code userId} is deliberately not carried. The view has it and every query narrows on it; a
 * client has no use for a value that is the same for every row in an answer. This is the reasoning
 * {@code TipRow} and {@code BookmarkRow} record for the same column.
 *
 * <p>A projection rather than a managed entity: every column here was computed by a view, and nothing
 * is written back through it - the write path is {@code sp_touch_recent_activity}, called by
 * {@code RecentActivityProcedureDao}.
 */
public record RecentActivityRow(
        Long transactionId,
        RecentAction action,
        LocalDateTime occurredAt,
        Long categoryId,
        CategoryType categoryType,
        BigDecimal amount,
        String encryptedDescription,
        LocalDate txnDate) {
}
