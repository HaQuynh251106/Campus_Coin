package com.campuscoin.admin.entity;

/**
 * Who an announcement is written for - {@code announcements.audience} exactly (UC-21 B1).
 *
 * <p>The three members are the column's own values, so a client switching on this field switches on
 * what the database holds and no translation table sits between them. The distinction is not
 * cosmetic: {@code DashboardViewDao} refuses {@link #ADMINS} to a student reader, so publishing a
 * notice to the wrong audience would show administrator-facing text on every student's dashboard.
 *
 * <p>A second enum beside {@code dashboard.entity.AnnouncementSeverity} rather than a shared one
 * because there is nothing to share: the severity enum already exists and is reused here, while
 * nothing in the build had ever named an audience until UC-21 gained a writer. Placing it in this
 * module keeps the type beside the only code that produces it.
 */
public enum AnnouncementAudience {

    /** Every signed-in account, students and administrators alike. */
    ALL,

    /** Students only. The column default, and the audience a student's dashboard admits. */
    STUDENTS,

    /** Administrators only. Never served to a student dashboard. */
    ADMINS
}
