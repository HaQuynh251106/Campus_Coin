package com.campuscoin.bookmark.entity;

/**
 * The two kinds of item {@code bookmarks} can hold (UC-19).
 *
 * <p><b>Both members are present even though only one is written.</b> {@code bookmarks.item_type} is
 * {@code ENUM('TIP','INSIGHT')} and UC-19 B1 names both - a student bookmarks "a tip or an insight".
 * The type therefore has to name the whole column to stay honest about the schema, and
 * {@code @Column(columnDefinition = "enum('TIP','INSIGHT')")} on {@code Bookmark} says the same thing
 * to {@code ddl-auto=validate}.
 *
 * <p><b>{@link #INSIGHT} is not written by this build, and that is recorded rather than hidden.</b>
 * Insights are UC-17, which sits inside module 12 - locked pending the project owner's approval - and
 * {@code insights} has no read path anywhere in the repository: no view in {@code db/02_views.sql}
 * and no endpoint. Implementing the insight branch would mean exposing a locked module's contract
 * through module 10, which the lock forbids. {@code BookmarkService} therefore refuses an
 * {@code INSIGHT} request with a field error that names the reason, rather than faking the branch or
 * silently narrowing UC-19. This is the same treatment module 9 gives {@code LOW_SAVINGS_RATE}: the
 * member exists because the database has it, and the module records what it does not yet do.
 *
 * <p>The name is read and written by name, never by ordinal, so a column value this enum does not
 * know about fails at the read instead of silently shifting meaning to the next member.
 */
public enum BookmarkItemType {

    /** A saving tip from {@code user_tips}. The branch this module implements (UC-18, UC-19). */
    TIP,

    /**
     * An insight from {@code insights}. UC-17 - module 12, locked. Never written by this module;
     * see the class note above.
     */
    INSIGHT
}
