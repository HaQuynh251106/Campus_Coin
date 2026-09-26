package com.campuscoin.recent.entity;

/**
 * The two values of {@code recent_activity.action} (UC-26).
 *
 * <p>Mirrors the column's ENUM exactly. The constant names are the members the database stores, and
 * nothing else is accepted: the API rejects a JSON number in their place, because a number would be
 * an ordinal whose meaning changes if these constants were ever reordered - the reasoning
 * {@code CategoryType} and {@code TransactionSource} record.
 *
 * <p><b>There are two actions and not one, and the difference is what UC-26 is about.</b> "Recently
 * viewed" and "recently edited" are different answers to the same question - the student is
 * returning to a record, either to look at it again or because they changed it - and a single
 * {@code TOUCHED} value could not tell them apart when the list is rendered. The column is what
 * distinguishes them, so this type is too.
 *
 * <p><b>{@code EDITED} is not written by the transaction endpoint.</b> Module 4's {@code PATCH
 * /api/v1/transactions/{id}} owns the edit and deliberately does not record one: recording an edit
 * is UC-26's, and having the edit path write into another module's table would make the transaction
 * module depend on this one. A client that wants the edit on the recent list records it here, with
 * the transaction's own {@code id}, and the database decides whether that is allowed. See
 * {@code docs/api/recent-activity.md}.
 *
 * <p>{@code action} is part of the row's identity - {@code uk_recent} is
 * {@code (user_id, transaction_id, action)} - so viewing and editing the same record are two rows
 * rather than one row that overwrites itself.
 */
public enum RecentAction {

    /** The student opened one of their own transactions to read it (UC-26). */
    VIEWED,

    /** The student changed one of their own transactions, and is recording that they did (UC-26). */
    EDITED
}
