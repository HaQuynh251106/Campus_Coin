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

@Entity
@Table(name = "bookmarks")
@DynamicUpdate
public class Bookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, columnDefinition = "enum('TIP','INSIGHT')")
    private BookmarkItemType itemType;

    @Column(name = "tip_id")
    private Long tipId;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Bookmark() {

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

    public String getNote() {
        return note;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setNote(String encryptedNote) {
        this.note = encryptedNote;
    }

    public static Bookmark newTipBookmark(Long userId, Long tipId, String encryptedNote) {
        Bookmark bookmark = new Bookmark();
        bookmark.userId = userId;
        bookmark.itemType = BookmarkItemType.TIP;
        bookmark.tipId = tipId;
        bookmark.note = encryptedNote;
        return bookmark;
    }
}
