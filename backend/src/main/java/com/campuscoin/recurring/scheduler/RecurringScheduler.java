package com.campuscoin.recurring.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.campuscoin.recurring.repository.RecurringProcedureDao;

/**
 * Runs the recurring-transaction scheduler on a timer (UC-09, BR-16).
 *
 * <p><b>Why the application runs this and not a database {@code EVENT}.</b> The schema starts MySQL
 * with {@code --event-scheduler=ON} and {@code docs/DB_DESIGN.md} records two ways to drive
 * {@code sp_post_recurring_transactions}: an {@code EVENT} that calls it daily, or the application
 * calling it on a schedule. An {@code EVENT} is a schema object, and the database is locked to the
 * merged artifact under {@code db/merged/} - so adding one would mean changing the database, which is
 * exactly what this project forbids. The application-side schedule is the other documented option
 * and needs no schema change.
 *
 * <p><b>Nothing here decides anything.</b> This class is a trigger and a log line. Which rules are
 * due, which periods are missing, and whether a rule has ended are all
 * {@code sp_post_recurring_transactions}'s, reached through {@link RecurringProcedureDao}. If this
 * class ever grows a condition beyond "should I run at all", that condition belongs in the
 * procedure instead.
 *
 * <p><b>Safe on more than one instance.</b> Every instance fires, and that is fine rather than
 * unfortunate: the procedure's {@code INSERT IGNORE} against {@code uk_occurrence_rule_period}
 * makes a duplicate period impossible, so concurrent runs collapse to one occurrence and one
 * transaction per period. No leader election and no lock is needed, and adding one would be the
 * application claiming a guarantee the database already provides (BR-16).
 *
 * <p><b>Disabled in tests.</b> {@code campuscoin.recurring.scheduler.enabled} is false in the test
 * environment so a timer cannot write rows in the middle of an assertion; the same procedure is
 * exercised directly, with explicit dates, through {@code RecurringProcedureDao}.
 */
@Component
@ConditionalOnProperty(name = "campuscoin.recurring.scheduler.enabled",
        havingValue = "true", matchIfMissing = true)
public class RecurringScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecurringScheduler.class);

    private final RecurringProcedureDao recurringProcedureDao;

    public RecurringScheduler(RecurringProcedureDao recurringProcedureDao) {
        this.recurringProcedureDao = recurringProcedureDao;
    }

    /**
     * Posts every occurrence that has come due.
     *
     * <p>The cron is a property so an operator can move the run without a rebuild; the default is
     * once a day shortly after midnight in the application's own zone. The exact minute is not
     * important - the procedure is date-based, not interval-based, so a late or repeated run posts
     * the same periods and no others.
     *
     * <p>{@code null} is passed rather than a date computed here, so "today" is the database
     * session's date in {@code +07:00}, the same clock the views and the BR-08 check use (VĐ-10). A
     * JVM in another zone would otherwise disagree with the database about the current day for part
     * of every day.
     *
     * <p>A failure is caught and logged rather than allowed to propagate. A scheduled method that
     * throws is logged by the framework and then simply not retried until the next tick, whereas
     * catching it here lets the message say what failed. Either way one bad run must not stop the
     * application - this is a background job, not a request.
     */
    @Scheduled(cron = "${campuscoin.recurring.scheduler.cron:0 5 0 * * *}",
            zone = "Asia/Ho_Chi_Minh")
    public void postDueOccurrences() {
        try {
            recurringProcedureDao.postDueOccurrences(null);
            log.info("Recurring scheduler run completed");
        } catch (RuntimeException ex) {
            // Logged with its cause so an operator can see which rule or constraint failed. The
            // procedure's text names rules and tables, which is right for a server log and is never
            // returned to a client.
            log.error("Recurring scheduler run failed", ex);
        }
    }
}
