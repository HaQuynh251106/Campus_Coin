package com.campuscoin.imports.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.campuscoin.category.entity.CategoryType;

/**
 * One CSV row as {@code import_rows} stores it (UC-11).
 *
 * <p><b>The owner is deliberately absent, and this one is a schema fact rather than a choice.</b>
 * {@code import_rows} has no {@code user_id} column at all - the schema's own comment says so, and
 * {@code DB_DESIGN.md} §4.8 records why: a second copy of the owner could disagree with the batch that
 * contains it, and nothing would keep the two in step. So the owner is always
 * {@code import_batches.user_id}, every read here joins through the batch, and there is no field for a
 * caller to have supplied.
 *
 * <p><b>{@code parsedDescription} holds PLAINTEXT, and it is the one field in this module that must
 * not be mistaken for an encrypted one.</b> Every other free-text field a student writes is stored as
 * an AES-256-GCM envelope, so a reader who has seen {@code TransactionMapper} will assume this one is
 * too. It is not:
 *
 * <ul>
 *   <li>{@code import_rows.parsed_description} is {@code VARCHAR(255)}, and the Base64 envelope of
 *       even a 160-character plaintext is longer than that. Encrypting it would need the column
 *       widened, which is a schema change this module does not make.</li>
 *   <li>The column is marked {@code KNOWN PLAINTEXT - RESIDUAL EXPOSURE, DELIBERATE} in
 *       {@code db/01_schema.sql} and named in OB-012, so encrypting only some of the values in it
 *       would make the schema comment untrue rather than make the data safe.</li>
 * </ul>
 *
 * <p><b>The consequence, and how far it now reaches (OB-018).</b> {@code sp_apply_csv_batch} inserts
 * this value straight into {@code transactions.description}, which <em>is</em> an encrypted column,
 * without encrypting it - a procedure cannot, because that would need the key inside MySQL. Until
 * OB-018 was resolved that was the end of the story and the transaction carried a plaintext
 * description for good. It is no longer: {@code ImportService#commit} re-encodes every description
 * the procedure just inserted, in the same transaction, before the response is built, so a committed
 * import leaves {@code transactions.description} in the one storage form the column is documented to
 * have. The value is written as plaintext by the procedure and does not stay that way.
 *
 * <p>{@code parsedDescription} itself is still plaintext, and that part is unchanged and not hidden:
 * the column is marked {@code KNOWN PLAINTEXT - RESIDUAL EXPOSURE, DELIBERATE} in
 * {@code db/01_schema.sql} and named in OB-012, because encrypting it would need the column widened
 * past {@code VARCHAR(255)} - the Base64 envelope of even a 160-character note is longer than that.
 * So the exposure this record describes is now confined to the staging table the preview reads, which
 * is a batch's own upload, rather than extending to the student's permanent record.
 *
 * <p>{@code rawData} is carried for the preview, so a client can show the student the line as it
 * arrived rather than a re-formatted version of it. It holds the same values as the {@code parsed_*}
 * fields and is plaintext for the same reason. It is held as the stored JSON <em>text</em> rather than
 * as a parsed object, because parsing it is a presentation decision and belongs in
 * {@code ImportMapper} - the one place that decides what leaves the server - rather than in a
 * projection that every reader of this record would then receive an object from. It is nullable: the
 * column is, and a row stored by a hand-run {@code INSERT} need not have one.
 *
 * <p>{@code transactionId} is non-null exactly when {@code rowStatus} is
 * {@link ImportRowStatus#IMPORTED}, and it is written only by {@code sp_apply_csv_batch}. It is carried
 * so the response can tell a client which record a row became.
 */
public record ImportRow(
        Long rowId,
        int csvRowNo,
        String rawData,
        LocalDate parsedDate,
        BigDecimal parsedAmount,
        CategoryType parsedType,
        String parsedDescription,
        String parsedCategoryName,
        Long resolvedCategoryId,
        Long aiSuggestedCategoryId,
        ImportRowStatus rowStatus,
        String errorMessage,
        Long transactionId) {
}
