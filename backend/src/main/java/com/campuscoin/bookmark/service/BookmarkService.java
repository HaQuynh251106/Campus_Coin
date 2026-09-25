package com.campuscoin.bookmark.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.bookmark.dto.BookmarkResponse;
import com.campuscoin.bookmark.dto.CreateBookmarkRequest;
import com.campuscoin.bookmark.dto.UpdateBookmarkNoteRequest;
import com.campuscoin.bookmark.entity.Bookmark;
import com.campuscoin.bookmark.entity.BookmarkItemType;
import com.campuscoin.bookmark.entity.BookmarkRow;
import com.campuscoin.bookmark.mapper.BookmarkMapper;
import com.campuscoin.bookmark.repository.BookmarkRepository;
import com.campuscoin.bookmark.repository.BookmarkViewDao;
import com.campuscoin.common.crypto.EncryptionService;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.BookmarkAlreadyExistsException;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RequestValidationException;

/**
 * Saving an item to look at again, noting why, and removing it (UC-19).
 *
 * <p><b>What the database owns, and this class therefore does not restate.</b>
 *
 * <ul>
 *   <li><b>That the item saved is the caller's own</b> - {@code trg_bookmarks_before_insert} and
 *       {@code trg_bookmarks_before_update} compare the target's owner with {@code NEW.user_id} and
 *       {@code SIGNAL} when they differ. That is BR-02, and it is enforced where it holds for every
 *       caller: the foreign key alone would only prove the row exists, not whose it is.
 *       {@code BookmarkRepository} holds no method that can load a tip, so this class cannot make a
 *       second, weaker decision about ownership - the trigger's refusal is translated on the way out
 *       by {@link #translateWriteFailure}.</li>
 *   <li><b>That an item is saved once</b> - {@code uk_bookmark_dedupe}, whose generated
 *       {@code dedupe_key} is {@code user_id|item_type|tip_id}. {@link #create} checks first so the
 *       caller gets a precise error, but the check is not the guarantee and is not presented as
 *       one.</li>
 *   <li><b>A bookmark's target columns</b> - {@code ck_bookmark_target} requires {@code tip_id} when
 *       {@code item_type} is {@code 'TIP'} and {@code insight_id} when it is {@code 'INSIGHT'}, and
 *       exactly one of the two. {@code Bookmark.newTipBookmark} writes both sides of that together, so
 *       a row violating it is unrepresentable rather than merely refused.</li>
 *   <li><b>When it was saved</b> - the {@code created_at} default, which is what the list orders by.</li>
 * </ul>
 *
 * <p><b>What is genuinely this class's.</b> That only the tip branch is served, that a note is
 * encrypted before it is stored, that the caller cannot reach another student's bookmark, and the
 * refusal of a duplicate save in words the caller can act on.
 *
 * <p><b>There is no user id parameter on any method.</b> The account comes from the verified token, so
 * there is no way to ask for somebody else's list, and every query is bound with the caller's id. That
 * is the whole of UC-19's ownership requirement at this layer (BR-02). It matters more than usual here
 * because a bookmark's whole content is prose about a student's own spending, plus a note they wrote
 * about it in their own words.
 *
 * <p><b>The insight branch is deliberately not served.</b> UC-19 B1 names an insight as well as a tip,
 * and {@code bookmarks.item_type} holds both, but insights are UC-17 - inside module 12, which is
 * locked pending the project owner's approval - and {@code insights} has no read path anywhere in this
 * repository: no view in {@code db/02_views.sql}, no endpoint. Serving the branch would mean exposing a
 * locked module's contract through this one, and faking it would be worse. The request is therefore
 * refused with a field error that names the reason, which is the treatment module 9 gives
 * {@code LOW_SAVINGS_RATE}: the state exists in the data and the module records that it does not serve
 * it, rather than pretending otherwise. The position is recorded in
 * {@code docs/OVERNIGHT_BLOCKERS.md}.
 */
@Service
public class BookmarkService {

    private static final Logger log = LoggerFactory.getLogger(BookmarkService.class);

    private final BookmarkRepository bookmarkRepository;
    private final BookmarkViewDao bookmarkViewDao;
    private final BookmarkMapper bookmarkMapper;
    private final EncryptionService encryptionService;

    public BookmarkService(BookmarkRepository bookmarkRepository,
                           BookmarkViewDao bookmarkViewDao,
                           BookmarkMapper bookmarkMapper,
                           EncryptionService encryptionService) {
        this.bookmarkRepository = bookmarkRepository;
        this.bookmarkViewDao = bookmarkViewDao;
        this.bookmarkMapper = bookmarkMapper;
        this.encryptionService = encryptionService;
    }

    /**
     * UC-19 B1, B3: save one of the caller's own tips, with an optional note, and return it.
     *
     * <p><b>Saving something already saved is a conflict, not a second row and not a silent
     * success.</b> {@code uk_bookmark_dedupe} would refuse the insert, and answering with the existing
     * row and a {@code 201} would tell the caller a bookmark was created when none was - and would
     * discard whatever note this request carried, leaving them believing it was stored. So the collision
     * is reported and the remedy is named: the item is already in their list, and the note on it is
     * changed through {@code PATCH}. The same treatment {@code BudgetAlreadyExistsException} gives the
     * same kind of collision for UC-13.
     *
     * <p><b>The note is encrypted here, before it reaches the entity.</b> This is the boundary the
     * whole encryption design rests on: {@code Bookmark#setNote} only ever receives an envelope, so no
     * other caller can store plaintext by forgetting a step, and a direct {@code SELECT} on
     * {@code bookmarks} shows nothing a student typed. A blank note becomes no note rather than an
     * envelope around an empty string - "you did not write one" and "you wrote spaces" are the same
     * thing to a reader, and storing the first as NULL is the honest form of it.
     *
     * <p><b>Ownership of the tip is the database's to decide.</b> This method does not load the tip
     * first: module 9 publishes no read that could load one without taking a write lock, and the
     * trigger already answers the question with the row in hand. A refusal arrives as a
     * {@code SIGNAL} and is translated into the same {@code 404} a missing tip produces, so the endpoint
     * cannot be used to discover which tip identifiers belong to other students (section 7.5).
     *
     * @throws RequestValidationException        the request names an insight, or names no item at all
     * @throws BookmarkAlreadyExistsException    the caller has already saved this tip
     * @throws NotFoundException                 no such tip, or not one of the caller's
     */
    @Transactional
    public BookmarkResponse create(AuthenticatedUser principal, CreateBookmarkRequest request) {
        Long userId = principal.userId();
        Long tipId = requireTipTarget(request);

        if (bookmarkRepository.existsByUserIdAndTipId(userId, tipId)) {
            throw new BookmarkAlreadyExistsException(
                    "This tip is already in your saved list.");
        }

        Bookmark bookmark = Bookmark.newTipBookmark(
                userId,
                tipId,
                encryptionService.encrypt(trimToNull(request.note())));

        try {
            bookmarkRepository.saveAndFlush(bookmark);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, userId, tipId);
        }

        log.info("Bookmark created userId={} bookmarkId={} tipId={}",
                userId, bookmark.getId(), tipId);

        // Read back through the same projection the list uses, so the created bookmark is described
        // exactly as it will be described from now on - including the note as the database stored it,
        // rather than as the request spelled it.
        return bookmarkMapper.toResponse(loadForResponse(userId, bookmark.getId()));
    }

    /**
     * UC-19 B4: every item the caller has saved, newest first.
     *
     * <p>{@code readOnly = true} documents that nothing is written - opening the saved list never
     * creates, re-orders or removes a bookmark, so a student cannot change their list by looking at it.
     *
     * <p><b>An empty list is a real answer, not a {@code 404}.</b> A student who has saved nothing has
     * an empty reference list, which is a fact about their own data rather than a missing resource.
     *
     * <p>A saved tip that was later dismissed on the tips screen is still listed. Keeping an item and
     * displaying it are different acts (VĐ-03), and B4's remedy for one no longer wanted is to un-mark
     * it - which is a request only the student can make. Filtering dismissed tips out here would remove
     * rows the student did not remove.
     */
    @Transactional(readOnly = true)
    public List<BookmarkResponse> list(AuthenticatedUser principal) {
        return bookmarkMapper.toResponses(bookmarkViewDao.findByUserId(principal.userId()));
    }

    /**
     * UC-19 B2: set or clear the note on one of the caller's own saved items.
     *
     * <p><b>Why this is an edit rather than a remove-and-save-again.</b> Recording a note by deleting
     * the bookmark and creating it again would give the new row a new {@code created_at}, move it to the
     * top of a list ordered by when it was saved, and re-fire a trigger whose purpose is to make a
     * bookmark's target unchangeable. The student's intent is to write a note, and this is the write
     * that does only that.
     *
     * <p><b>Absent and null mean "leave it"; the empty string means "remove it".</b> The same convention
     * {@code UpdateCategoryRequest} uses, so absent/null can go on meaning "I did not touch this" and
     * clearing has a spelling of its own. An empty or whitespace-only value stores null - a note
     * consisting of spaces is no note.
     *
     * <p><b>The entity is loaded by id <em>and</em> owner, and no lock is taken.</b> Unlike a tip's
     * state, nothing here is decided from the row's current value: this is a last-write-wins field edit,
     * which is what the caller asked for, so serialising it would cost contention for no invariant.
     * Because the load already named the caller, the update needs no ownership predicate of its own -
     * and the update trigger re-checks the target regardless.
     *
     * @throws NotFoundException if the bookmark does not exist or is not the caller's
     */
    @Transactional
    public BookmarkResponse updateNote(AuthenticatedUser principal, Long bookmarkId,
                                       UpdateBookmarkNoteRequest request) {
        Long userId = principal.userId();

        Bookmark bookmark = bookmarkRepository.findByIdAndUserId(bookmarkId, userId)
                .orElseThrow(() -> new NotFoundException("Bookmark not found."));

        if (request.note() != null) {
            bookmark.setNote(encryptionService.encrypt(trimToNull(request.note())));

            try {
                bookmarkRepository.saveAndFlush(bookmark);
            } catch (RuntimeException ex) {
                throw translateWriteFailure(ex, userId, bookmark.getTipId());
            }

            log.info("Bookmark note updated userId={} bookmarkId={}",
                    userId, bookmarkId);
        }

        return bookmarkMapper.toResponse(loadForResponse(userId, bookmarkId));
    }

    /**
     * UC-19 B4: un-mark an item the student no longer needs.
     *
     * <p><b>Idempotent, and deliberately so.</b> Removing something already gone answers {@code 204},
     * which is what "it is not in my list" means - the treatment logout gives a second logout. A
     * {@code 404} would make a retry, or two devices acting at once, look like a failure and invite the
     * client to show an error for an end state the caller wanted.
     *
     * <p>The row is loaded by id and owner and deleted, so a bookmark belonging to another student is
     * not found rather than found and refused. Deleting it can never touch the tip it pointed at: the
     * foreign key runs from {@code bookmarks} to {@code user_tips} with {@code ON DELETE CASCADE}, so
     * removing a tip takes its bookmarks with it - never the reverse, and {@code Bookmark} has no
     * cascade of its own.
     */
    @Transactional
    public void delete(AuthenticatedUser principal, Long bookmarkId) {
        Long userId = principal.userId();

        bookmarkRepository.findByIdAndUserId(bookmarkId, userId).ifPresentOrElse(bookmark -> {
            bookmarkRepository.delete(bookmark);
            log.info("Bookmark removed userId={} bookmarkId={}", userId, bookmarkId);
        }, () -> log.info("Bookmark removal was a no-op userId={} bookmarkId={}",
                userId, bookmarkId));
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /**
     * The tip a create request names, or a refusal saying why there is not one.
     *
     * <p>Three things can be wrong with the target, and each is answered in the terms the caller used:
     * an insight is a real value of {@code item_type} that this build does not serve, so it is refused
     * by name with the reason rather than as a bad enum member; a {@code TIP} with no identifier is
     * incomplete; and a non-positive identifier cannot exist, so it is refused before a pointless round
     * trip rather than reported later as a missing tip.
     */
    private Long requireTipTarget(CreateBookmarkRequest request) {
        if (request.itemType() == BookmarkItemType.INSIGHT) {
            throw new RequestValidationException(
                    "Insights cannot be saved in this build.",
                    List.of(new ApiError.FieldError("itemType",
                            "Insights (UC-17) are not enabled yet, so only TIP can be saved. "
                                    + "The column accepts INSIGHT and the endpoint will serve it once "
                                    + "UC-17 is approved.")));
        }

        Long itemId = request.itemId();
        if (itemId == null || itemId <= 0) {
            throw new RequestValidationException(
                    "The item to save was not identified.",
                    List.of(new ApiError.FieldError("itemId",
                            "Provide the id of the tip to save, as the tips endpoint returns it.")));
        }

        return itemId;
    }

    /**
     * One bookmark as the list would describe it, from the projection rather than the entity.
     *
     * <p>Every write's response goes through the same read as the list, so a client renders one shape.
     * A row that has just been written is always found by its owner; if it were not, that would mean the
     * caller's own write vanished between the flush and the read, which is a fault rather than a missing
     * resource - hence {@code IllegalStateException}, answered as a server error, rather than a
     * {@code 404} that would blame the caller's request for it.
     */
    private BookmarkRow loadForResponse(Long userId, Long bookmarkId) {
        return bookmarkViewDao.findOne(userId, bookmarkId)
                .orElseThrow(() -> new IllegalStateException(
                        "Bookmark " + bookmarkId + " was not readable back after being written."));
    }

    /**
     * Turns a database refusal into the API error that explains it.
     *
     * <p>Which rule fired is decided by {@link BookmarkWriteFailure}, which asks the database by
     * SQLSTATE and by constraint name rather than by matching the driver's message. Anything it does not
     * recognise is rethrown unchanged, so {@code GlobalExceptionHandler} answers it as a generic conflict
     * or an internal error rather than this method mislabelling it.
     *
     * <p><b>The {@code SIGNAL} and the missing-foreign-key cases collapse into one answer, and that is
     * the point.</b> {@code 45000} is the trigger refusing a tip that is not the caller's; {@code 23000}
     * on {@code fk_bookmark_tip} is a tip that does not exist. A caller who was told which of the two
     * happened could enumerate other students' tip identifiers one request at a time (section 7.5), so
     * both are reported as "not found" - the same indistinguishability
     * {@code UserTipRepository#findByIdAndUserId} achieves by returning empty for both.
     *
     * <p>The exception is deliberately not logged. MySQL's duplicate-key message carries the constraint
     * name and the dedupe key, and the trigger's names the rule and the table - all internal identifiers
     * the response already withholds. The user id and the tip id describe the refusal well enough to
     * investigate it.
     *
     * @param userId the owning student, for the log line - never the message
     * @param tipId  the tip involved, for the same reason
     */
    private RuntimeException translateWriteFailure(RuntimeException ex, Long userId, Long tipId) {
        if (BookmarkWriteFailure.isDuplicateBookmark(ex)) {
            log.info("Bookmark write rejected by the dedupe key userId={} tipId={}", userId, tipId);
            return new BookmarkAlreadyExistsException("This tip is already in your saved list.");
        }

        if (BookmarkWriteFailure.isSignalledRefusal(ex)) {
            log.info("Bookmark write rejected by a trigger userId={} tipId={}", userId, tipId);
            return new NotFoundException("Tip not found.");
        }

        if (BookmarkWriteFailure.isConstraintViolation(ex)) {
            log.info("Bookmark write rejected by a constraint userId={} tipId={}", userId, tipId);
            return new NotFoundException("Tip not found.");
        }

        // Not one of the recognised refusals, so it is a genuine fault rather than a rule doing its
        // job. Logged in full and answered as an internal error by the handler.
        log.error("Bookmark write failed unexpectedly userId={} tipId={}", userId, tipId, ex);
        return ex;
    }

    /** Trims, and turns a value that is empty after trimming into null. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
