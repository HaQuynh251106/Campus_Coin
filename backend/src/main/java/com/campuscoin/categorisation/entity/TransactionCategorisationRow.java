package com.campuscoin.categorisation.entity;

import java.math.BigDecimal;

import com.campuscoin.category.entity.CategoryType;

/**
 * One of the student's own records, as UC-08 reads it to record what the system suggested.
 *
 * <p><b>Six columns, and the first two are the student's own choice.</b> {@code categoryId} is what the
 * record is filed under - set through module 4, never through this module - and {@code categoryType} is
 * that category's type, read here because a learned rule stores the type beside the category. Taking
 * the choice from the record rather than from the request is what makes {@code ai_overridden} a fact the
 * server derived instead of a claim a client made, and it means no endpoint of this module accepts a
 * category id at all.
 *
 * <p><b>{@code description} is carried as it is stored, which is an AES-256-GCM envelope.</b> The name
 * says so, so a caller that reads it has to decrypt it first - which
 * {@code CategorisationService} does, and the decrypted text is what the suggestion is computed from
 * and what a learned keyword is drawn from. Carrying it decrypted here would make "a row holds
 * ciphertext" false for this projection, the reasoning {@code FlaggedTransactionRow} records for the
 * same column. It is nullable: the schema allows a record with no description, and there is then
 * nothing to categorise.
 *
 * <p><b>The three {@code ai_} columns are the reason this projection exists.</b> The recorder compares
 * what it is about to write against what is already stored, so that a request which would change
 * nothing issues no {@code UPDATE} - the rule {@code AnomalyDetector#differsFromStored} establishes, and
 * here it matters for the same reason: {@code trg_transactions_after_update} appends a
 * {@code transaction_history} row when one of these three columns moves, and a write that moved nothing
 * would put noise into the log BR-09 keeps.
 *
 * <p><b>{@code aiOverridden} is a primitive.</b> The column is {@code NOT NULL DEFAULT 0}, so a null
 * would mean the projection read the wrong thing; reading it as {@code false} in that case is the
 * reading that cannot turn a record into one the student is told they corrected.
 *
 * <p>{@code userId} is absent for the usual reason - every query already narrowed on it.
 */
public record TransactionCategorisationRow(
        Long categoryId,
        CategoryType categoryType,
        String encryptedDescription,
        Long aiSuggestedCategoryId,
        BigDecimal aiConfidence,
        boolean aiOverridden) {
}
