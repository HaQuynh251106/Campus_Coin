package com.campuscoin.bookmark.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.campuscoin.tips.entity.TipState;

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
