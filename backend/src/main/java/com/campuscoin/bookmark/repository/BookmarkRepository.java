package com.campuscoin.bookmark.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.campuscoin.bookmark.entity.Bookmark;

/**
 * MySQL access for {@code bookmarks} (UC-19).
 *
 * <p><b>Every query takes the owning student's id.</b> There is no method that can load a bookmark by
 * id alone, so no service method can act on another student's bookmark by mistake. BR-02 requires
 * ownership server-side, and making it a property of the query rather than a check at the call site is
 * the version that cannot be forgotten - the same arrangement {@code UserTipRepository} and
 * {@code CategoryRepository} use.
 *
 * <p><b>Why there is no {@code JOIN FETCH} of the saved tip, and no association to it at all.</b> The
 * bookmark list has to show the tip's title, body and saving, so something must read those columns -
 * but a {@code @ManyToOne} to {@code UserTip} would put this module's <em>writes</em> behind module
 * 9's entity: building a bookmark would mean loading a {@code UserTip}, and the only ownership-scoped
 * finder module 9 publishes takes a {@code PESSIMISTIC_WRITE} lock, because it exists for state
 * changes. Taking a write lock on a tip in order to insert an unrelated bookmark would be wrong twice
 * over - it locks a row this transaction does not modify, and it would make bookmarking contend with a
 * pin. So the target is a plain {@code tip_id}, and the display columns are read by
 * {@link BookmarkViewDao} as a projection over the join, which is the shape this codebase uses for
 * every read that spans tables without changing them.
 *
 * <p><b>Deliberately no lock on {@link #findByIdAndUserId}.</b> Module 9 locks a tip because its state
 * change is <em>decided</em> from the state the row holds, and two decisions taken from lock-free reads
 * can both be taken. Nothing here is decided from the note's current value: setting a note is a plain
 * last-write-wins field edit, which is exactly what the caller asked for, and serialising it would
 * cost contention for no invariant. Deleting is likewise unconditional. Contrast
 * {@code UserTipRepository#findByIdAndUserId}, where the lock is load-bearing.
 */
public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    /**
     * UC-19 B4: one of the caller's own bookmarks, for reading its note back or removing it.
     *
     * <p>Returns empty both when the bookmark does not exist and when it belongs to someone else - the
     * two are indistinguishable from the outside, on purpose (section 7.5), so this endpoint cannot be
     * used to discover which bookmark identifiers exist.
     *
     * <p>Named for the pair it filters on rather than the pair module 9 uses, so the ownership
     * requirement is visible at every call site: a bookmark is reached by its id <em>and</em> its
     * owner's.
     */
    @Query("""
            SELECT b FROM Bookmark b
             WHERE b.id = :id AND b.userId = :userId
            """)
    Optional<Bookmark> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * UC-19 B1: has the caller already saved this tip?
     *
     * <p><b>This is a pre-check for a precise error, not the guarantee.</b> The guarantee is
     * {@code uk_bookmark_dedupe}, whose {@code dedupe_key} is {@code user_id|item_type|tip_id} - so two
     * requests that interleave between this check and the insert are still stopped, and the resulting
     * constraint violation is recognised by {@code BookmarkWriteFailure}. Answering the ordinary case
     * here is what lets the caller be told "you already saved this" instead of receiving a generic
     * refused write; the same division {@code CategoryService} uses for a duplicate name.
     *
     * <p>A derived query rather than a {@code @Query}: it is a single equality on two plain mapped
     * columns, and Spring Data's derivation is both correct and clearer than the SQL it would replace.
     */
    boolean existsByUserIdAndTipId(Long userId, Long tipId);
}
