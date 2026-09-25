package com.campuscoin.reports.entity;

/**
 * The granularity a report is broken down by (UC-15).
 *
 * <p>The two members are the two breakdowns the schema actually computes, and there are deliberately
 * no others. {@code DAILY} reads {@code v_daily_spending_current_month} and {@code WEEKLY} reads
 * {@code v_weekly_spending_current_month}; both group expense transactions the same way, one by
 * calendar day and one by ISO week. A third value - "by month", say - would be a breakdown no view
 * produces, and adding one here would mean the API offering a grouping the database has not defined.
 *
 * <p><b>Both members describe the current month only.</b> Neither view takes a month parameter; both
 * derive their range from {@code CURDATE()} inside the database session. That is why the report
 * endpoint treats the request's {@code from}/{@code to} as a window <em>within</em> the current month
 * and answers {@code 400} when they fall outside it, rather than accepting a range it cannot honour -
 * see {@code ReportService#resolveScope}.
 */
public enum ReportGranularity {

    /** One point per calendar day on which the student spent something. */
    DAILY,

    /** One point per ISO week that contains a day of the current month. */
    WEEKLY
}
