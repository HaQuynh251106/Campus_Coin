package com.campuscoin.tips.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.campuscoin.tips.repository.TipGenerationDao;

/**
 * Generates the month's saving tips on a timer (UC-18, BR-14).
 *
 * <p><b>Why the application runs this and not a database {@code EVENT}.</b> The schema starts MySQL
 * with {@code --event-scheduler=ON} and records {@code sp_generate_tips} as callable both ways, the
 * same choice {@code docs/DB_DESIGN.md} records for {@code sp_post_recurring_transactions}. An
 * {@code EVENT} is a schema object, and the database is locked to the merged artifact under
 * {@code db/merged/} - adding one would change the database, which this project forbids. The
 * application-side schedule is the other documented option and needs no schema change.
 *
 * <p><b>Nothing here decides anything.</b> This class is a trigger and a log line. Which tips a
 * student gets, how many, what they say and what they are worth are all {@code sp_generate_tips}'s,
 * reached through {@link TipGenerationDao}. If this class ever grows a condition beyond "should I run
 * at all", that condition belongs in the procedure instead.
 *
 * <p><b>Safe on more than one instance.</b> Every instance fires, and that is fine rather than
 * unfortunate: the procedure's {@code INSERT IGNORE} against {@code uk_tip_dedupe} makes a second
 * tip for the same student, month and rule impossible, so concurrent runs collapse to one tip each
 * and a student's pinned or dismissed tips are left exactly as they were. No leader election and no
 * lock is needed, and adding one would be the application claiming a guarantee the database already
 * provides - the same reasoning {@code RecurringScheduler} records (BR-16).
 *
 * <p><b>Disabled in tests.</b> {@code campuscoin.tips.scheduler.enabled} is false in the test
 * environment, so a timer cannot write {@code user_tips} rows in the middle of an assertion. The
 * same procedure is exercised directly, with explicit months, through {@link TipGenerationDao} and
 * the generate endpoint.
 *
 * <p><b>It does not replace the on-demand endpoint.</b> This runs once a day, so a student who
 * records a large purchase would otherwise wait until the next tick to see the advice it should
 * produce; {@code POST /api/v1/tips/generate} runs the same generator for the caller immediately.
 * Neither is the primary path - both call one procedure.
 */
@Component
@ConditionalOnProperty(name = "campuscoin.tips.scheduler.enabled",
        havingValue = "true", matchIfMissing = true)
public class TipGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(TipGenerationScheduler.class);

    private final TipGenerationDao tipGenerationDao;

    public TipGenerationScheduler(TipGenerationDao tipGenerationDao) {
        this.tipGenerationDao = tipGenerationDao;
    }

    /**
     * Generates this month's tips for every active student.
     *
     * <p>The cron is a property so an operator can move the run without a rebuild; the default is
     * once a day shortly after midnight in the application's own zone, a few minutes after the
     * recurring-transaction run so a tip generated on the 1st is computed from the transactions that
     * run has just posted. The exact minute is not important - the procedure is month-based, not
     * interval-based, so a late or repeated run produces the same tips and no others.
     *
     * <p>{@code null} is passed rather than a month computed here, so the month is the database
     * session's, in the {@code +07:00} the session is pinned to (VĐ-10). A JVM in another zone would
     * otherwise disagree with the database about the current month for part of every day - the same
     * reasoning {@code RecurringScheduler} gives for the day.
     *
     * <p>A failure is caught and logged rather than allowed to propagate. A scheduled method that
     * throws is logged by the framework and then simply not retried until the next tick, whereas
     * catching it here lets the message say which run failed. Either way one bad run must not stop
     * the application - this is a background job, not a request.
     */
    @Scheduled(cron = "${campuscoin.tips.scheduler.cron:0 10 0 * * *}",
            zone = "Asia/Ho_Chi_Minh")
    public void generateMonthlyTips() {
        try {
            int students = 0;
            int failures = 0;

            // One call and one transaction per student, rather than one transaction for the run. A
            // student whose generation fails then costs only their own tips: everything generated
            // before them is already committed, and the failure does not roll the run back. A single
            // transaction over every student would mean one bad row stopping the whole campus's tips
            // for the night.
            for (Long studentId : tipGenerationDao.findActiveStudentIds()) {
                try {
                    tipGenerationDao.generateTips(studentId, null);
                    students++;
                } catch (RuntimeException ex) {
                    failures++;
                    log.error("Saving-tip generation failed userId={}", studentId, ex);
                }
            }

            log.info("Saving-tip scheduler run completed students={} failures={}", students, failures);
        } catch (RuntimeException ex) {
            // The run itself failed - most likely the student list could not be read. Logged with
            // its cause so an operator can see what broke; the procedure's text names tables and
            // settings, which is right for a server log and is never returned to a client.
            log.error("Saving-tip scheduler run failed", ex);
        }
    }
}
