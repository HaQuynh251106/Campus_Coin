package com.campuscoin.anomaly.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.campuscoin.category.entity.CategoryType;

/**
 * One of the student's own records, as UC-24 reads it (UC-24).
 *
 * <p><b>One projection serves both the list and the detector, and that is the point.</b> The
 * endpoint that lists what is flagged and the scan that decides what should be read the same columns,
 * so a row that the detector marked is a row the list can render without a second query and without a
 * second definition of what a flagged record carries. Two projections would let the two answers drift,
 * which is the failure {@code RecentActivityRow}'s note records for the same reason.
 *
 * <p><b>The three flag columns are carried, not just {@code isFlagged}.</b> The detector compares what
 * it computed against what is stored so that a rescan reaching the same conclusion issues no write at
 * all - the procedure would suppress the history row, but not the round trip. The stored
 * {@code flagType} is what makes that comparison possible; {@code flagNote} is carried because a
 * record whose note the detector no longer agrees with has to be rewritten even when the type matches.
 *
 * <p><b>{@code description} is carried as it is stored, which is an AES-256-GCM envelope.</b> The name
 * says so, so a caller that publishes this field has to decrypt it first - which is what
 * {@code AnomalyMapper} does. Carrying it decrypted here would make "a row holds ciphertext" false for
 * this one projection, the reasoning {@code RecentActivityRow} records.
 *
 * <p><b>{@code userId} is deliberately not carried.</b> Every query narrows on it, so it is the same
 * value on every row of an answer and a client has no use for it - the reasoning {@code TipRow},
 * {@code BookmarkRow} and {@code RecentActivityRow} all record.
 *
 * <p>A projection rather than a managed entity: nothing is written back through it. The write path is
 * {@code sp_flag_transaction}, called by {@code AnomalyFlagProcedureDao}, and the detector never hands
 * an entity to Hibernate - which is what keeps a JPA flush from competing with the procedure over the
 * three columns the transaction module deliberately leaves unmapped.
 */
public record FlaggedTransactionRow(
        Long transactionId,
        Long categoryId,
        String categoryName,
        CategoryType categoryType,
        BigDecimal amount,
        LocalDate txnDate,
        String encryptedDescription,
        Boolean isFlagged,
        AnomalyFlagType flagType,
        String flagNote) {
}
