package com.campuscoin.bookmark.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.campuscoin.tips.entity.TipState;

/**
 * One row of a bookmark joined to the tip it saves (UC-19 B4).
 *
 * <p><b>Why the tip's own fields are part of this projection.</b> UC-19's postcondition is that a
 * marked item "can be looked at again in later sessions", so the list endpoint has to return enough of
 * the tip to render the card the student saved - not just an identifier they would have to resolve
 * with a second request, month by month. Reading the tip's display columns here is what makes the
 * bookmark list one query.
 *
 * <p><b>{@code encryptedNote} is the stored envelope, not the student's words.</b> This type carries
 * the column as it sits in the database; {@code BookmarkMapper} is the one place it becomes readable.
 * The field is named for what it holds so a caller cannot mistake it for plaintext - the same reason
 * {@code Bookmark#getNote} says so on the entity.
 *
 * <p><b>{@code tipId} is carried and {@code insightId} is not.</b> The insight branch is UC-17, inside
 * the locked module 12, and this module never writes or reads one; carrying a column that is always
 * null would advertise a capability the build does not have. See {@link BookmarkItemType}.
 *
 * <p>A projection rather than a managed entity: every value here is read, and the one column a
 * student can change is written through {@link Bookmark}, which needs the row itself rather than a
 * view of it.
 */
public record BookmarkRow(
        Long bookmarkId,
        BookmarkItemType itemType,
        Long tipId,
        String encryptedNote,
        LocalDateTime createdAt,
        String tipTitle,
        String tipBody,
        BigDecimal potentialSaving,
        TipState tipState,
        LocalDate periodMonth) {
}
