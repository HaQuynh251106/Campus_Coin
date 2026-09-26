package com.campuscoin.categorisation.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.categorisation.entity.TransactionCategorisationRow;
import com.campuscoin.common.crypto.EncryptionService;

/**
 * Reads the one encrypted column UC-08 needs, and turns advice into the response fields.
 *
 * <p>Its own class, and a plain one rather than a mapping library, for the reason the other mappers
 * give: it is the single place that decides what may leave the server and in what form.
 *
 * <p><b>{@code description} is decrypted here, and this is the only place it happens.</b> As stored the
 * column is an AES-256-GCM envelope, so the keyword the system learns, the text the suggester sees and
 * the text a provider would be sent are all produced from this one call. A second decryption site
 * would be a second definition of "the student's description", and the two could disagree about a row
 * written before encryption was enabled. The tolerant variant ({@code decryptStored}) is used so such a
 * row still reads rather than failing the request; {@code AnomalyMapper}, {@code TransactionMapper} and
 * {@code RecentActivityMapper} make the same choice for the same column.
 *
 * <p>Nothing is recalculated and nothing is cached: the row arrives as the query returned it and the
 * decrypted text is handed straight on. The plaintext is never logged, never stored and never returned
 * to the client as such - the response carries the keyword the system derived from it, which is the
 * normalised form and not the original.
 */
@Component
public class CategorisationMapper {

    private final EncryptionService encryptionService;

    public CategorisationMapper(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    /**
     * The student's own words for the record, or {@code null} when the record has none.
     *
     * <p>A {@code null} answer is ordinary rather than a fault: the schema allows a record with no
     * description, and such a record is simply not categorisable. The suggester treats it as "nothing to
     * propose" and the learner derives no keyword from it, so the request still succeeds and still
     * reports the record's state.
     */
    public String description(TransactionCategorisationRow row) {
        return encryptionService.decryptStored(row.encryptedDescription());
    }
}
