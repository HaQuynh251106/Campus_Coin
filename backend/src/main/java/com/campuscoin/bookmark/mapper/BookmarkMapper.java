package com.campuscoin.bookmark.mapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.bookmark.dto.BookmarkResponse;
import com.campuscoin.bookmark.entity.BookmarkRow;
import com.campuscoin.common.crypto.EncryptionService;

@Component
public class BookmarkMapper {

    private final EncryptionService encryptionService;

    public BookmarkMapper(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    public List<BookmarkResponse> toResponses(List<BookmarkRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }

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

    private String toMonthString(LocalDate periodMonth) {
        return periodMonth == null ? null : YearMonth.from(periodMonth).toString();
    }
}
