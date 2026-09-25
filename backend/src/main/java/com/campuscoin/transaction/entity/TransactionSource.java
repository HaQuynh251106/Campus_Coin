package com.campuscoin.transaction.entity;

/**
 * How a transaction came to exist, as {@code transactions.source} stores it.
 *
 * <p>Mirrors the column's ENUM exactly. The value is provenance, not a rule: it records whether a
 * student typed the record, imported it from a file (UC-11) or had it generated from a recurring
 * template (UC-09), so a later screen can explain where a row came from.
 *
 * <p>It is also read by {@code sp_validate_transaction}: a {@code RECURRING} row is the one case
 * allowed a future date, because the scheduler writes those ahead of time (BR-08). That makes this
 * value security-relevant - a client able to set it could record a future-dated transaction - which
 * is why no request DTO in this module has a field for it. Every row this module writes is
 * {@link #MANUAL}.
 */
public enum TransactionSource {

    /** Entered by the student through the application (UC-07). The only value this module writes. */
    MANUAL,

    /** Created by the CSV import flow (UC-11, module 12). */
    CSV,

    /** Created by the recurring scheduler (UC-09, module 5). */
    RECURRING
}
