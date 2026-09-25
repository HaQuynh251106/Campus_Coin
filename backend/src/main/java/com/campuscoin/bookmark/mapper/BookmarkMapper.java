package com.campuscoin.bookmark.mapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.bookmark.dto.BookmarkResponse;
import com.campuscoin.bookmark.entity.BookmarkRow;
import com.campuscoin.common.crypto.EncryptionService;

/**
 * Turns UC-19's bookmark rows into the responses (UC-19).
 *
 * <p>Its own class, and a plain one rather than a mapping library, for the reason the other mappers
 * give: it is the single place that decides which columns may leave the server. The projection carries
 * {@code user_id} on the row the query narrowed by, and the joined tip's {@code rank_score} is not
 * selected at all; centralising the projection here means a column added to either table cannot
 * silently appear in a response.
 *
 * <p><b>{@code note} is decrypted here, and this is the only place it happens.</b> As stored it is an
 * AES-256-GCM envelope, so a mapper that published the column would be publishing ciphertext; doing it
 * in the mapper rather than in each caller keeps the rule "an entity holds ciphertext, a response holds
 * plaintext" true for every path that returns a bookmark, including the ones added later. Decryption is
 * the tolerant variant ({@code decryptStored}), so a row written before encryption was enabled - or by
 * a hand-run {@code INSERT} during development - still reads correctly rather than failing the whole
 * response. See {@code docs/SECURITY.md} §12.
 *
 * <p><b>Nothing is recalculated.</b> The list arrives in the order the DAO asked for and is passed
 * through unaltered; the tip's title and body were rendered by the database from a template, and the
 * amounts inside them are the same figures every other part of the application reports. Re-sorting or
 * re-rendering here would be a second definition of a fact the read already fixed.
 *
 * <p>The one conversion owned here is the month: the database stores the tip's {@code period_month} as
 * the {@code DATE} of the first day and a client names it as {@code yyyy-MM}. It is the same conversion
 * {@code TipMapper}, {@code ReportMapper} and {@code BudgetMapper} make, so a month is one string for
 * the same month wherever it is read.
 */
@Component
public class BookmarkMapper {

    private final EncryptionService encryptionService;

    public BookmarkMapper(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    /** UC-19 B4: the caller's saved items, in the order the query returned them. */
    public List<BookmarkResponse> toResponses(List<BookmarkRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }

    /**
     * UC-19: one saved item as the client sees it.
     *
     * <p>Deliberately omitted: the owner, and the tip's {@code categoryId} and {@code rankScore}. The
     * owner is what the query already applied and a client has no use for it; the category is not
     * selected, because the saved advice names its own subject in prose; and the rank score is what the
     * tips screen orders by, which is a different screen's business - the bookmark list orders by when
     * the item was saved.
     */
    public BookmarkResponse toResponse(BookmarkRow row) {
        return new BookmarkResponse(
                row.bookmarkId(),
                row.itemType(),
                row.tipId(),
                row.tipTitle(),
                row.tipBody(),
                row.potentialSaving(),
                row.tipState(),
                toMonthString(row.periodMonth()),
                encryptionService.decryptStored(row.encryptedNote()),
                row.createdAt());
    }

    /**
     * A first-of-month {@code DATE} as the {@code yyyy-MM} a client uses.
     *
     * <p>{@link YearMonth#from} rather than slicing the string: every {@code period_month} the database
     * produces is the first of its month, so the year and the month are the whole of what it carries,
     * and taking them through the date type cannot produce a value the calendar disagrees with. Null in,
     * null out - a bookmark whose tip could not be joined has no month to name.
     */
    private String toMonthString(LocalDate periodMonth) {
        return periodMonth == null ? null : YearMonth.from(periodMonth).toString();
    }
}
