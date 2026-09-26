package com.campuscoin.imports.entity;

/**
 * The five values of {@code import_batches.status} (UC-11).
 *
 * <p>Mirrors the column's ENUM exactly. The constant names are the members the database stores, and
 * nothing else is accepted: a JSON number in their place is rejected, because a number would be an
 * ordinal whose meaning changes if these constants were ever reordered - the reasoning
 * {@code CategoryType}, {@code TransactionSource} and {@code AnomalyFlagType} all record.
 *
 * <p><b>Two of the five are written by this application and two are not.</b>
 * {@link #PREVIEWED} is set by the upload endpoint, which is the preview; {@link #CANCELLED} is set
 * by the cancel endpoint. {@link #COMMITTED} is set by {@code sp_apply_csv_batch} and never by Java -
 * the procedure owns the commit step, and the batch's counters are rewritten from its own walk of the
 * rows in the same statement. {@link #FAILED} is written by nobody in this build: it exists so a batch
 * that could not be processed at all can be recorded, and the upload path either stores a batch with
 * its rows or stores nothing - it does not leave a half-built batch behind to be marked failed.
 *
 * <p><b>{@link #UPLOADED} is accepted but not produced.</b> The schema defaults to it, so a batch
 * created by a hand-run {@code INSERT} during development reads as {@code UPLOADED} until it is
 * previewed. The commit endpoint accepts it alongside {@code PREVIEWED} because
 * {@code sp_apply_csv_batch}'s own guard does - a batch that arrived with rows and was never explicitly
 * previewed is still a batch the student confirmed.
 */
public enum ImportBatchStatus {

    /** Created, not yet previewed. The schema's default; this build previews within the same request. */
    UPLOADED,

    /** Parsed and stored, and the student has been shown the rows. This build's resting state. */
    PREVIEWED,

    /** {@code sp_apply_csv_batch} has walked the rows and generated the transactions. */
    COMMITTED,

    /** The student abandoned the batch (UC-11 A2): nothing was imported and nothing will be. */
    CANCELLED,

    /** The batch could not be processed. A member this build does not write - see the class note. */
    FAILED;

    /**
     * Whether the batch can still be changed or committed.
     *
     * <p>The one place this question is answered in Java, because four call sites ask it - the
     * override, the commit and the cancel each have to refuse a settled batch, and the response
     * publishes it as {@code modifiable} so a client can hide the controls. It states the same rule
     * {@code sp_apply_csv_batch} applies with
     * {@code IF v_batch_st NOT IN ('UPLOADED','PREVIEWED')}, and it is deliberately the same set rather
     * than a looser one: an open batch is exactly one the procedure would still accept.
     */
    public boolean isOpen() {
        return this == UPLOADED || this == PREVIEWED;
    }
}
