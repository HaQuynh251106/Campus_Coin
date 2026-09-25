package com.campuscoin.bookmark.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;

/**
 * A row of {@code bookmarks}: one item a student saved to look at again (UC-19).
 *
 * <p><b>A bookmark is not a pin, and the two are kept apart on purpose (VĐ-03).</b> Pinning is
 * {@code user_tips.state = 'PINNED'}, it lives on the tip row, and it is about <em>display order</em>:
 * a pinned tip leads the dashboard and the tips screen. A bookmark is this table, and it is about
 * <em>keeping</em>: it saves an item into a reference list that survives the session, which is
 * UC-19's postcondition. The same tip can be both, and the two are changed by different endpoints on
 * different rows. The note in {@code db/01_schema.sql} above this table records the same distinction,
 * and UC-19's own BA note (VĐ-03) is what settled it.
 *
 * <p><b>{@code note} holds ciphertext, not the student's words.</b> The column is a Base64
 * AES-256-GCM envelope written by the application, so a direct {@code SELECT} on this table does not
 * reveal what the student typed. {@code BookmarkService} encrypts on the way in and {@code
 * BookmarkMapper} decrypts on the way out; nothing between the two ever sees plaintext except the
 * request and the response. See {@code docs/SECURITY.md} §12.
 *
 * <p><b>Columns deliberately left unmapped.</b>
 *
 * <ul>
 *   <li>{@code dedupe_key} is a {@code VIRTUAL} generated column that exists only so
 *       {@code uk_bookmark_dedupe} can stop the same item being bookmarked twice. Nothing reads it,
 *       and mapping it would invite Hibernate to try to write a value the database computes itself -
 *       the same reason {@code scope_key} is unmapped on {@code Category} and {@code dedupe_key} on
 *       {@code UserTip}.</li>
 *   <li>{@code insight_id} is unmapped, so this entity cannot express an insight bookmark at all.
 *       That is the scope decision made structural rather than merely documented: the insight branch
 *       is UC-17 (module 12, locked), and an entity with no way to set the column is one more place
 *       the lock holds. {@code ck_bookmark_target} requires {@code insight_id} to be NULL when
 *       {@code item_type} is {@code 'TIP'}, and leaving the column out of the INSERT lets the
 *       database's own default supply that NULL.</li>
 * </ul>
 *
 * <p>{@link DynamicUpdate} is applied so changing a note writes only {@code note} and not the
 * target columns beside it, rather than writing every mapped column back with the values read when
 * the request began. Re-writing {@code item_type} and {@code tip_id} would also re-fire
 * {@code trg_bookmarks_before_update}, which exists to make a bookmark's target unchangeable.
 */
@Entity
@Table(name = "bookmarks")
@DynamicUpdate
public class Bookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The owning student. Never published; used by every ownership query.
     *
     * <p>Mapped as a plain identifier rather than an association: nothing here needs the student's
     * row, and a {@code @ManyToOne} would load a full {@code User} - password hash and token version
     * included - to answer a question about a saved tip.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Whether this bookmark points at a tip or an insight.
     *
     * <p>{@code columnDefinition} reproduces the schema's ENUM for the same reason it does on
     * {@code UserTip} and {@code Category}: Hibernate validates a column by the type name MySQL
     * reports, and an {@code @Enumerated(STRING)} field would otherwise be expected to be a
     * {@code varchar}, which {@code ddl-auto=validate} would refuse.
     *
     * <p>This module writes {@code TIP} only - see {@link BookmarkItemType}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, columnDefinition = "enum('TIP','INSIGHT')")
    private BookmarkItemType itemType;

    /**
     * The {@code user_tips} row this bookmark saves.
     *
     * <p>Nullable in the schema because the INSIGHT branch uses the other target column, but never
     * null on a row this module writes: {@code ck_bookmark_target} requires it for
     * {@code item_type = 'TIP'}, and {@link #newTipBookmark} is the only way to build one.
     *
     * <p>The foreign key is {@code ON DELETE CASCADE}, so a tip that disappears takes its bookmark
     * with it. Ownership is not this column's business: {@code trg_bookmarks_before_insert} checks
     * that the tip belongs to {@link #userId}, because the foreign key alone would only prove the row
     * exists - BR-02.
     */
    @Column(name = "tip_id")
    private Long tipId;

    /**
     * The student's own short note about what they saved, as an AES-256-GCM envelope (VĐ-02).
     *
     * <p>Nullable and optional: UC-19 B2 allows a note, it does not require one. A bookmark without a
     * note is the common case and is stored as NULL - not as an empty envelope, which would be a
     * distinguishable value carrying no meaning.
     *
     * <p>The field is a {@code String} over a {@code VARCHAR(2048) CHARACTER SET ascii COLLATE
     * ascii_bin} column, and the two widths differ in the direction that matters: 2048 is the
     * envelope's worst case, and 255 is the plaintext the API accepts. The same arrangement
     * {@code Transaction.description} uses, and the reason {@code VARCHAR} was chosen over
     * {@code VARBINARY} at all - a Base64 envelope is pure ASCII, so it maps straight onto a
     * {@code String} and {@code ddl-auto=validate} stays meaningful.
     */
    @Column(name = "note", length = 255)
    private String note;

    /** When the student saved it. Set by the database default; the list is ordered by it. */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Bookmark() {
        // Required by JPA.
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public BookmarkItemType getItemType() {
        return itemType;
    }

    public Long getTipId() {
        return tipId;
    }

    /** The stored envelope, not the student's words. Callers that publish it must decrypt. */
    public String getNote() {
        return note;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // --- Writable by UC-19 -----------------------------------------------------------------------
    // There is deliberately no setter for user_id, item_type, tip_id or created_at. A bookmark's
    // owner and target are what it means, and trg_bookmarks_before_update refuses a re-pointed
    // target at the database anyway; a setter here would only offer a write the database rejects.

    /**
     * Replaces the note, or clears it.
     *
     * <p>The only field UC-19 lets a student change about a bookmark. B4 offers "un-mark when no
     * longer needed" - that is {@code DELETE}, not this - and nothing in the use case offers moving a
     * bookmark onto a different item, which would be a different bookmark rather than an edit of this
     * one.
     *
     * @param encryptedNote the envelope from {@code EncryptionService}, or null to clear the note.
     *                      Never plaintext: encryption belongs to the service boundary, and a
     *                      parameter named for what it holds is harder to pass the wrong thing to.
     */
    public void setNote(String encryptedNote) {
        this.note = encryptedNote;
    }

    /**
     * Builds the bookmark UC-19 creates: one of the student's own tips, with an optional note.
     *
     * <p>{@code item_type} is written explicitly rather than left to the column's lack of a default,
     * and it is written as the constant {@link BookmarkItemType#TIP} rather than taken from a
     * parameter - the insight branch is not this module's to create, so there is no argument through
     * which one could be asked for. {@code insight_id} is not named at all, which is what lets the
     * column's NULL default satisfy {@code ck_bookmark_target}.
     *
     * <p>Nothing here checks that the tip exists or belongs to {@code userId}. Both are BR-02's, and
     * the database enforces them: {@code fk_bookmark_tip} refuses a tip that is not there and
     * {@code trg_bookmarks_before_insert} refuses one that is not the student's. Checking in Java
     * would be a second opinion about a rule the trigger already holds for every caller.
     *
     * @param userId       the owning student, never null
     * @param tipId        the {@code user_tips} row to save, never null
     * @param encryptedNote the envelope for the note, or null for a bookmark without one
     */
    public static Bookmark newTipBookmark(Long userId, Long tipId, String encryptedNote) {
        Bookmark bookmark = new Bookmark();
        bookmark.userId = userId;
        bookmark.itemType = BookmarkItemType.TIP;
        bookmark.tipId = tipId;
        bookmark.note = encryptedNote;
        return bookmark;
    }
}
