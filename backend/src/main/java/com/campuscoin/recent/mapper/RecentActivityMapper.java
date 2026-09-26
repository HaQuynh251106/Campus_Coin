package com.campuscoin.recent.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.common.crypto.EncryptionService;
import com.campuscoin.recent.dto.RecentActivityResponse;
import com.campuscoin.recent.entity.RecentActivityRow;

/**
 * Turns UC-26's activity rows into the responses.
 *
 * <p>Its own class, and a plain one rather than a mapping library, for the reason the other mappers
 * give: it is the single place that decides which columns may leave the server. The projection already
 * withholds {@code user_id}; centralising the conversion here means a column added to the view cannot
 * silently appear in a response.
 *
 * <p><b>{@code description} is decrypted here, and this is the only place it happens.</b> As stored it
 * is an AES-256-GCM envelope, so a mapper that published the column would be publishing ciphertext.
 * Doing it here rather than in the service keeps the rule "a row holds ciphertext, a response holds
 * plaintext" true for every path that returns an entry, including the one a later module might add.
 * The tolerant variant ({@code decryptStored}) is used so a row written before encryption was enabled -
 * or by a hand-run {@code CALL} during development - still reads rather than failing the whole list;
 * {@code TransactionMapper} makes the same choice for the same column.
 *
 * <p><b>The service returns the row rather than a response so this class can decrypt it</b>, which is
 * what keeps the decryption off the write path: {@code RecentActivityService} never holds a
 * plaintext description, and only this class ever does.
 *
 * <p><b>Nothing is recalculated.</b> The list arrives in the order the DAO asked for and is passed
 * through unaltered; the amount, the type and the date are the values the view read, not values
 * re-derived from them. Re-sorting or re-deriving here would be a second definition of a fact the read
 * already fixed.
 *
 * <p>Nothing is converted. The columns this row carries are already in the shapes the API publishes -
 * a {@code DATETIME} becomes a {@code LocalDateTime}, a {@code DATE} becomes a {@code LocalDate} - so
 * unlike {@code TipMapper} and {@code BookmarkMapper} there is no month to render as {@code yyyy-MM}
 * and no figure to format.
 */
@Component
public class RecentActivityMapper {

    private final EncryptionService encryptionService;

    public RecentActivityMapper(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    /** UC-26: the caller's entries, in the order the query returned them. */
    public List<RecentActivityResponse> toResponses(List<RecentActivityRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }

    /**
     * UC-26: one entry as the client sees it.
     *
     * <p>Deliberately omitted: the owner, which the query already applied and which is the same value
     * on every row of an answer.
     */
    public RecentActivityResponse toResponse(RecentActivityRow row) {
        return new RecentActivityResponse(
                row.transactionId(),
                row.action(),
                row.occurredAt(),
                row.categoryId(),
                row.categoryType(),
                row.amount(),
                encryptionService.decryptStored(row.encryptedDescription()),
                row.txnDate());
    }
}
