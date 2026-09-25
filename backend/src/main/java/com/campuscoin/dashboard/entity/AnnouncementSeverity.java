package com.campuscoin.dashboard.entity;

/**
 * How prominently an announcement should be shown (UC-12 B3, UC-21 B2).
 *
 * <p>The three members are {@code announcements.severity} exactly, in the database's own words, so a
 * client can map each to a colour without a translation table. The value is what tells a dashboard
 * whether it is showing a routine notice or a warning, which is why it is published rather than
 * flattened into the prose.
 *
 * <p><b>This enum reads announcements; it does not create them.</b> Writing one is UC-21, which
 * belongs to the administration module and to its own procedures
 * ({@code sp_admin_create_announcement}); the dashboard has no route that writes an announcement and
 * no schema that would let a student author one.
 */
public enum AnnouncementSeverity {

    /** A routine notice. */
    INFO,

    /** Something the student should act on. */
    WARNING,

    /** A positive notice, such as a completed import. */
    SUCCESS
}
