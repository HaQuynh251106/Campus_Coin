package com.campuscoin.anomaly.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.anomaly.dto.FlaggedTransactionResponse;
import com.campuscoin.anomaly.entity.FlaggedTransactionRow;
import com.campuscoin.common.crypto.EncryptionService;

/**
 * Turns UC-24's flagged rows into the responses.
 *
 * <p>Its own class, and a plain one rather than a mapping library, for the reason the other mappers
 * give: it is the single place that decides which columns may leave the server. The projection already
 * withholds {@code user_id}; centralising the conversion here means a column added to the query cannot
 * silently appear in a response.
 *
 * <p><b>{@code description} is decrypted here, and this is the only place it happens.</b> As stored it
 * is an AES-256-GCM envelope, so a mapper that published the column would be publishing ciphertext.
 * Doing it here rather than in the service keeps the rule "a row holds ciphertext, a response holds
 * plaintext" true for every path that returns an entry - and this module has two of them, the read and
 * the scan's read-back, both of which come through this class. The tolerant variant
 * ({@code decryptStored}) is used so a record written before encryption was enabled - or by a hand-run
 * {@code CALL} during development - still reads rather than failing the whole list;
 * {@code TransactionMapper} and {@code RecentActivityMapper} make the same choice for the same column.
 *
 * <p><b>{@code flagNote} is published exactly as the detector stored it.</b> It is not encrypted - the
 * schema does not encrypt it, and it holds no free text the student typed: every sentence this module
 * writes is composed from the category's own name and the figures the comparison used. It is passed
 * through rather than rebuilt, because rebuilding it here would be a second definition of the
 * explanation the response and the database are supposed to agree on.
 *
 * <p>Nothing is recalculated. The list arrives in the order the query asked for and is passed through
 * unaltered; the amount, the type and the date are the values the query read, not values re-derived
 * from them.
 */
@Component
public class AnomalyMapper {

    private final EncryptionService encryptionService;

    public AnomalyMapper(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    /** UC-24: the caller's flagged records, in the order the query returned them. */
    public List<FlaggedTransactionResponse> toResponses(List<FlaggedTransactionRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }

    /**
     * UC-24: one marked record as the client sees it.
     *
     * <p>Deliberately omitted: the owner, which the query already applied and which is the same value
     * on every row of an answer.
     */
    public FlaggedTransactionResponse toResponse(FlaggedTransactionRow row) {
        return new FlaggedTransactionResponse(
                row.transactionId(),
                row.categoryId(),
                row.categoryName(),
                row.categoryType(),
                row.amount(),
                row.txnDate(),
                encryptionService.decryptStored(row.encryptedDescription()),
                row.isFlagged(),
                row.flagType(),
                row.flagNote());
    }
}
