package com.campuscoin.imports.entity;

/**
 * The five values of {@code import_rows.row_status} (UC-11).
 *
 * <p>Mirrors the column's ENUM exactly, for the reason the sibling enums record.
 *
 * <p><b>Who writes which member is the whole design of UC-11, so it is stated here.</b>
 *
 * <ul>
 *   <li>{@link #VALID}, {@link #ERROR} and {@link #DUPLICATE} are written by this application when the
 *       file is parsed and previewed. The database has no CSV parser and no procedure that inserts
 *       into {@code import_rows} at all - an audit of {@code db/02_views.sql} and
 *       {@code db/04_triggers.sql} finds no view and no trigger over these two tables - so the
 *       application owns everything up to the commit.</li>
 *   <li>{@link #IMPORTED} is written by {@code sp_apply_csv_batch} and never by Java. The procedure
 *       walks the rows and sets it on each one it generated a transaction for, together with the
 *       {@code transaction_id} it created. Java must not write it, and cannot: nothing in this package
 *       updates a row to {@code IMPORTED}.</li>
 *   <li>{@link #SKIPPED} is written by nobody in this build. A duplicate is recorded as
 *       {@link #DUPLICATE} - the schema has a member for it, and a student reading the preview needs
 *       to see <em>why</em> a row will not be imported rather than that it was passed over. The commit
 *       procedure counts every row that is neither imported nor an error as
 *       {@code import_batches.duplicate_rows}, which is why {@code DUPLICATE} rows are not left in a
 *       provisional state: they are already in the shape the procedure counts.</li>
 * </ul>
 *
 * <p>{@link #ERROR} and {@link #DUPLICATE} are both "this row will not become a transaction", and they
 * are kept apart because the student's remedy differs: an invalid row has to be fixed in the file and
 * uploaded again, while a duplicate is a row they may already have recorded and can simply drop. A
 * single "rejected" state would leave the preview unable to say which.
 */
public enum ImportRowStatus {

    /** Parsed, valid, and will become a transaction if the batch is committed. */
    VALID,

    /** Not importable: the row could not be parsed, or the database would refuse it. */
    ERROR,

    /** Valid in itself, but a record the student appears to have already entered. */
    DUPLICATE,

    /** {@code sp_apply_csv_batch} generated a transaction for this row. Written only by the procedure. */
    IMPORTED,

    /** A member this build does not write - see the class note. */
    SKIPPED;

    /**
     * Whether committing the batch will attempt this row.
     *
     * <p>True only for {@link #VALID}, which is exactly the predicate {@code sp_apply_csv_batch}'s
     * cursor carries ({@code WHERE batch_id = p_batch_id AND row_status = 'VALID'}). Stated here so
     * the preview's own counters and the procedure's walk cannot disagree about which rows are
     * importable - they are the same rule written once for Java and once in SQL, and this method is
     * where a test can pin the Java half against the other.
     */
    public boolean isImportable() {
        return this == VALID;
    }
}
