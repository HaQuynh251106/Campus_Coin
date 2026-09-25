package com.campuscoin.transaction.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.category.entity.Category;
import com.campuscoin.common.crypto.EncryptionService;
import com.campuscoin.transaction.dto.TransactionResponse;
import com.campuscoin.transaction.entity.Transaction;

/**
 * Maps a {@link Transaction} row to the API model (UC-07, UC-10).
 *
 * <p>Its own class, and a plain one rather than a mapping library, for the same reason
 * {@code CategoryMapper} and {@code ProfileMapper} are: it is the single place that decides which
 * columns may leave the server. The entity carries {@code userId} and the soft-delete pair, and once
 * later modules map their columns it will carry the AI suggestion and anomaly flags too;
 * centralising the choice here means a field can be added to the entity without it silently
 * appearing in a response.
 *
 * <p>The flattening of the category happens here. {@code TransactionResponse} sends
 * {@code categoryId}, {@code categoryName}, {@code categoryIcon} and {@code categoryColor} as
 * siblings rather than as a nested object, and {@code type} is lifted out of the category for the
 * same reason: it is not a column of {@code transactions} at all, and reading it from
 * {@link Category#getType()} is what makes BR-05 hold by construction rather than by agreement
 * between two stored values.
 *
 * <p>{@code type} is sent as the category's own enum rather than as a string, so it serialises to
 * the same two member names the category endpoints publish. A client reading {@code type} from a
 * category and one from a transaction sees one vocabulary.
 *
 * <p>The category is never null in practice: the column is NOT NULL and every query that loads a
 * transaction joins it. The mapping reads it through one local variable rather than repeating
 * {@code transaction.getCategory()} five times, so there is a single place where that assumption
 * lives.
 *
 * <p><b>{@code description} is decrypted here.</b> As stored it is an AES-256-GCM envelope, and
 * this method is the one place it becomes readable again. Doing it in the mapper rather than in
 * each caller keeps the rule "an entity holds ciphertext, a response holds plaintext" true for
 * every path that returns a transaction, including the ones added later. Decryption is the
 * tolerant variant ({@code decryptStored}), so a row written before encryption was enabled - or
 * by the recurring scheduler, which copies the rule's own envelope - still reads correctly rather
 * than failing the whole response. See {@code docs/SECURITY.md}.
 */
@Component
public class TransactionMapper {

    private final EncryptionService encryptionService;

    public TransactionMapper(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    /**
     * UC-07, UC-10: one transaction as the client sees it.
     *
     * <p>Deliberately omitted: the owner, and the row's creation and modification timestamps. The
     * AI suggestion columns and the anomaly flags are not omitted here - they are not mapped on the
     * entity at all, so they cannot reach this method.
     */
    public TransactionResponse toResponse(Transaction transaction) {
        Category category = transaction.getCategory();

        return new TransactionResponse(
                transaction.getId(),
                category.getId(),
                category.getName(),
                category.getIcon(),
                category.getColor(),
                category.getType(),
                transaction.getAmount(),
                transaction.getTxnDate(),
                encryptionService.decryptStored(transaction.getDescription()),
                transaction.getSource(),
                transaction.getIsDeleted(),
                transaction.getDeletedAt());
    }
}
