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

        return bookmarkMapper.toResponse(loadForResponse(userId, bookmark.getId()));
    }

    @Transactional(readOnly = true)
    public List<BookmarkResponse> list(AuthenticatedUser principal) {
        return bookmarkMapper.toResponses(bookmarkViewDao.findByUserId(principal.userId()));
    }

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

    @Transactional
    public void delete(AuthenticatedUser principal, Long bookmarkId) {
        Long userId = principal.userId();

        bookmarkRepository.findByIdAndUserId(bookmarkId, userId).ifPresentOrElse(bookmark -> {
            bookmarkRepository.delete(bookmark);
            log.info("Bookmark removed userId={} bookmarkId={}", userId, bookmarkId);
        }, () -> log.info("Bookmark removal was a no-op userId={} bookmarkId={}",
                userId, bookmarkId));
    }

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

    private BookmarkRow loadForResponse(Long userId, Long bookmarkId) {
        return bookmarkViewDao.findOne(userId, bookmarkId)
                .orElseThrow(() -> new IllegalStateException(
                        "Bookmark " + bookmarkId + " was not readable back after being written."));
    }

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

        log.error("Bookmark write failed unexpectedly userId={} tipId={}", userId, tipId, ex);
        return ex;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
