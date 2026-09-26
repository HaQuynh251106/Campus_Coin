package com.campuscoin.anomaly.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.campuscoin.anomaly.entity.AnomalyFlagType;
import com.campuscoin.anomaly.entity.CategoryAmountStats;
import com.campuscoin.anomaly.entity.FlaggedTransactionRow;
import com.campuscoin.common.setting.SettingReader;

/**
 * Decides which of a student's records look wrong, and says why (UC-24).
 *
 * <p><b>This is the whole of UC-24's judgement, and it is deliberately in Java rather than in SQL.</b>
 * The three flag columns, {@code ix_txn_flagged} and both {@code anomaly.*} settings have existed since
 * the schema was written, but no view ever read them and no procedure wrote them - the feature was
 * module 12 and had not been built, and the one write path this module adds,
 * {@code sp_flag_transaction}, is deliberately the only one. The usual rule in this project is that a
 * rule spanning rows belongs in the database, and it was considered here: a detector in SQL would have
 * to be a stored procedure, and MySQL cannot decrypt {@code description} - which is fine, because the
 * detector does not read it - but it also could not format a note the student reads, and, more to the
 * point, a procedure that both decided and wrote would be a second write path onto columns
 * {@code sp_flag_transaction} was written to own. So the database keeps the two things it is uniquely
 * able to keep - ownership and the shape of the stored value - and the arithmetic lives here, where a
 * test can pin it exactly.
 *
 * <p><b>Two findings, and the type column holds one of them.</b> A record is flagged
 * {@code DUPLICATE} or {@code UNUSUAL_AMOUNT}, never both, because {@code flag_type} is one column.
 * Duplicate is evaluated first and wins: it is the more specific claim - "you entered this twice" is a
 * statement about this record and another one, where "this is large" is a statement about this record
 * against an average - and the two can coincide. When they do, the student's remedy is the same and it
 * is the duplicate one: remove the extra record, and a later scan re-examines what is left. So a
 * record wrongly kept as a duplicate does not stay mislabelled; correcting the data refines the
 * finding on the next scan.
 *
 * <p><b>What each finding means, stated precisely, because a student is shown prose that depends on
 * it.</b>
 *
 * <ul>
 *   <li><b>{@code DUPLICATE}</b> - another of the same student's live records has the same
 *       {@code category_id} and the same {@code amount}, and its date is within
 *       {@code anomaly.duplicate_window_days} days either side. The record flagged is the
 *       <em>later</em> of the pair by id, and its note names the earlier one's date: the student's
 *       remedy is to decide which of the two is the mistake and remove it, and the later record is
 *       the one that reads as the second entry of a thing already entered once - which is exactly
 *       what the note says. Only earlier records are compared, so each record can be flagged at most
 *       once and a chain of three identical records flags two of them, each against its own nearest
 *       earlier twin.</li>
 *   <li><b>{@code UNUSUAL_AMOUNT}</b> - the amount is at least {@code anomaly.unusual_multiplier}
 *       times the student's own average in that category, <em>not counting this record</em>. The
 *       exclusion is the substance of the rule: including the record in its own baseline would let one
 *       large figure raise the average it is measured against, so a student with two records of 10 and
 *       900 would not be told that 900 is unusual. Excluding it makes the check mean what the setting
 *       says - "three times what I normally spend here". A category with no other record has no
 *       baseline at all, so nothing in it is unusual; one record is a figure, not a habit.</li>
 * </ul>
 *
 * <p><b>Nothing here writes.</b> It returns a verdict per record and {@code AnomalyService} compares
 * each against what is stored, calling {@code sp_flag_transaction} only where the two differ. That
 * split is what keeps a repeated scan from writing history rows for records it reached the same
 * conclusion about.
 *
 * <p><b>The verdict is a pure function of the rows and the two settings</b>, so the same data always
 * produces the same answer and a scan is idempotent by construction rather than by a guard.
 */
@Component
public class AnomalyDetector {

    /** UC-24: the seeded default for {@code anomaly.duplicate_window_days}, used when it is unusable. */
    static final int DEFAULT_DUPLICATE_WINDOW_DAYS = 3;

    /**
     * UC-24: the seeded default for {@code anomaly.unusual_multiplier}.
     *
     * <p>Three times, which is the value the schema's own comment documents. It is read rather than
     * written into the comparison below because VĐ-05 makes it configuration - an administrator
     * retunes it without a redeploy.
     */
    static final BigDecimal DEFAULT_UNUSUAL_MULTIPLIER = new BigDecimal("3");

    /**
     * The width of {@code transactions.flag_note}.
     *
     * <p>Named here because the notes this class writes name the category, and {@code categories.name}
     * is itself {@code VARCHAR(80)}: a name near its limit plus the figures would overrun the column,
     * and MySQL would truncate the note or refuse the write depending on the session's strict mode.
     * Rather than cut a sentence off mid-word, a note that would not fit falls back to a shorter one -
     * see {@link #duplicateNote} and {@link #unusualNote}.
     */
    static final int MAX_NOTE_LENGTH = 255;

    private final SettingReader settingReader;

    public AnomalyDetector(SettingReader settingReader) {
        this.settingReader = settingReader;
    }

    /**
     * UC-24: what the detector concluded about one record.
     *
     * <p>{@code flagNote} is null exactly when {@code flagType} is {@link AnomalyFlagType#NONE}, which
     * is the stored shape: clearing a flag nulls the note, so a stale explanation cannot survive it. A
     * note carries no timestamp and no identifier, so re-running the detector over unchanged data
     * produces the identical string and the write is skipped.
     */
    public record Verdict(Long transactionId, AnomalyFlagType flagType, String flagNote) {
    }

    /**
     * UC-24: a verdict for every record examined, in the order the records arrived.
     *
     * <p>Every candidate gets a verdict, including the ones that are fine - that is why
     * {@link AnomalyFlagType#NONE} is a member of the enum rather than an absence. A record that was
     * flagged and has since been corrected, or whose duplicate has been trashed, is answered
     * {@code NONE}, and the service writes that back so the flag is cleared. A scan that only ever
     * reported findings could never undo one, and correcting the record is the student's whole remedy.
     *
     * @param candidates the student's own live records, in id order - the order is not cosmetic, it is
     *                   what lets the duplicate rule name a stable "earlier" record
     * @param categoryStats each of the student's categories' count and total, for the average baseline
     * @param windowDays how many days either side of a record count as the same moment in time
     * @param multiplier how many times the usual amount counts as unusual
     */
    public List<Verdict> detect(List<FlaggedTransactionRow> candidates,
                                Map<Long, CategoryAmountStats> categoryStats,
                                int windowDays,
                                BigDecimal multiplier) {
        List<Verdict> verdicts = new ArrayList<>(candidates.size());

        for (int index = 0; index < candidates.size(); index++) {
            FlaggedTransactionRow record = candidates.get(index);

            FlaggedTransactionRow earlierTwin = earlierDuplicateOf(record, candidates, index, windowDays);
            if (earlierTwin != null) {
                verdicts.add(new Verdict(record.transactionId(), AnomalyFlagType.DUPLICATE,
                        duplicateNote(record, earlierTwin)));
                continue;
            }

            if (isUnusuallyLarge(record, categoryStats.get(record.categoryId()), multiplier)) {
                verdicts.add(new Verdict(record.transactionId(), AnomalyFlagType.UNUSUAL_AMOUNT,
                        unusualNote(record, categoryStats.get(record.categoryId()))));
                continue;
            }

            verdicts.add(new Verdict(record.transactionId(), AnomalyFlagType.NONE, null));
        }

        return verdicts;
    }

    /** UC-24: how many days either side of a record count as the same moment in time. */
    int duplicateWindowDays() {
        return settingReader.getInt(SettingReader.ANOMALY_DUPLICATE_WINDOW_DAYS,
                DEFAULT_DUPLICATE_WINDOW_DAYS);
    }

    /** UC-24: how many times the student's own average counts as unusual. */
    BigDecimal unusualMultiplier() {
        return settingReader.getDecimal(SettingReader.ANOMALY_UNUSUAL_MULTIPLIER,
                DEFAULT_UNUSUAL_MULTIPLIER);
    }

    // ------------------------------------------------------------------
    //  The two findings
    // ------------------------------------------------------------------

    /**
     * The nearest earlier record this one duplicates, or null when it duplicates nothing.
     *
     * <p><b>Only earlier records are compared, and that is what makes the rule order-independent.</b>
     * The candidate list is in id order, so "earlier" is "lower id" and the comparison is made against
     * a prefix of the list that does not change when a later record is added or removed. Comparing in
     * both directions would flag each of a pair against the other - two flags for one mistake, and two
     * history rows - and would make which record is reported depend on which one the scan happened to
     * visit first.
     *
     * <p>"Earlier" is by id rather than by {@code txn_date}, because two records can share a date and
     * the rule has to name one of them deterministically. The date is what the window is measured on,
     * and it is measured in both directions: a record entered today about last week is still a
     * duplicate of one entered yesterday about last week. What the window rejects is two records whose
     * <em>dates</em> are far apart, which is the case where the same amount in the same category is
     * plausibly two real purchases rather than one entered twice.
     *
     * <p>The nearest twin is chosen so the note can name a specific date, and it is chosen by id
     * order - so it is the most recent of the earlier twins rather than an arbitrary one. When several
     * qualify, any of them supports the same finding; naming a stable one is what keeps the note, and
     * therefore the write comparison, deterministic.
     */
    private FlaggedTransactionRow earlierDuplicateOf(FlaggedTransactionRow record,
                                                     List<FlaggedTransactionRow> candidates,
                                                     int index,
                                                     int windowDays) {
        for (int earlierIndex = index - 1; earlierIndex >= 0; earlierIndex--) {
            FlaggedTransactionRow other = candidates.get(earlierIndex);

            if (!Objects.equals(other.categoryId(), record.categoryId())) {
                continue;
            }
            // compareTo, not equals: the column is DECIMAL(15,2), so BigDecimal.equals would treat
            // 25.0 and 25.00 as different amounts. Amounts arrive from the database at a fixed scale
            // today, but the comparison is the rule and should not depend on that.
            if (other.amount().compareTo(record.amount()) != 0) {
                continue;
            }
            if (Math.abs(ChronoUnit.DAYS.between(other.txnDate(), record.txnDate())) > windowDays) {
                continue;
            }
            // Returning the first match walking backwards means the nearest earlier twin, which is
            // the one the note names - so the date the student is told to look for is the most recent
            // time they entered this, not the oldest.
            return other;
        }

        return null;
    }

    /**
     * Whether the record is at least the configured multiple of the student's usual amount there.
     *
     * <p><b>The record is excluded from its own baseline.</b> See the class note: including it would
     * let a large figure inflate the average it is measured against, and would make the check fail
     * precisely in the small-category case where it is most obviously right. A category the student has
     * no other record in has no average to be measured against, so the answer is no - a baseline of one
     * record is that record, and calling it unusual for being itself would flag every new category.
     *
     * <p>{@code compareTo} rather than {@code >=} on doubles: the money figures are {@code BigDecimal}
     * and the threshold is a decimal setting, so the comparison is exact and a value exactly at the
     * multiple counts as unusual - "at least three times", which is how the setting reads.
     */
    private boolean isUnusuallyLarge(FlaggedTransactionRow record,
                                     CategoryAmountStats stats,
                                     BigDecimal multiplier) {
        if (stats == null || stats.recordCount() <= 1) {
            return false;
        }

        BigDecimal othersTotal = stats.totalAmount().subtract(record.amount());
        long othersCount = stats.recordCount() - 1;
        BigDecimal othersAverage = othersTotal.divide(BigDecimal.valueOf(othersCount), 6,
                RoundingMode.HALF_UP);
        BigDecimal threshold = othersAverage.multiply(multiplier);

        return record.amount().compareTo(threshold) >= 0;
    }

    // ------------------------------------------------------------------
    //  The notes
    // ------------------------------------------------------------------

    /**
     * Why a record is flagged as a duplicate, in the student's own terms.
     *
     * <p>Names the other record's date so the student can find it, and the category, and gives the
     * action: check whether the record was entered twice. Both facts are already in the row the client
     * was handed, so the note is not the only place they exist - it is what makes the flag explain
     * itself where it is displayed, without the client having to join two rows to write a sentence.
     *
     * <p>Falls back to a generic sentence when the formatted one would not fit the column - see
     * {@link #MAX_NOTE_LENGTH}.
     */
    private String duplicateNote(FlaggedTransactionRow record, FlaggedTransactionRow earlierTwin) {
        String note = "This looks like a record you already entered: the same amount in "
                + record.categoryName() + " on " + earlierTwin.txnDate()
                + ". Check whether it was recorded twice.";

        return fits(note)
                ? note
                : "This looks like a record you already entered. Check whether it was recorded twice.";
    }

    /**
     * Why a record is flagged as unusually large, with the average it was measured against.
     *
     * <p>The figures are stated because the finding is a comparison: "much higher than usual" without
     * the numbers would leave the student unable to judge whether the flag is right, and the average
     * is computed from their own records and published nowhere else. The count of other records goes
     * with it, because an average over two records is a far weaker claim than one over twelve and the
     * student is the only one who can tell which they are looking at.
     *
     * <p>Falls back to a generic sentence when the formatted one would not fit the column.
     */
    private String unusualNote(FlaggedTransactionRow record, CategoryAmountStats stats) {
        long othersCount = stats.recordCount() - 1;
        BigDecimal othersAverage = stats.totalAmount().subtract(record.amount())
                .divide(BigDecimal.valueOf(othersCount), 2, RoundingMode.HALF_UP);

        String note = "Much higher than your usual " + record.categoryName() + " amount: "
                + record.amount().setScale(2, RoundingMode.HALF_UP) + " against your average of "
                + othersAverage + " over your other " + othersCount
                + (othersCount == 1 ? " record" : " records") + " in this category.";

        return fits(note)
                ? note
                : "This amount is much higher than your usual spending in this category.";
    }

    private static boolean fits(String note) {
        return note.length() <= MAX_NOTE_LENGTH;
    }

    /**
     * Whether a found verdict differs from what the row already stores.
     *
     * <p>Lives here rather than in the service so the comparison and the verdict are stated together:
     * both the type and the note have to match for a record to be left alone. Comparing the type only
     * would leave a corrected note behind - the window or the multiplier could have been retuned, and
     * the sentence the student reads would then describe a rule that is no longer running.
     */
    static boolean differsFromStored(FlaggedTransactionRow row, Verdict verdict) {
        if (row.flagType() != verdict.flagType()) {
            return true;
        }
        if (verdict.flagType() == AnomalyFlagType.NONE) {
            // The stored note must be null for a cleared flag; if it is not, the row needs rewriting.
            return row.flagNote() != null || Boolean.TRUE.equals(row.isFlagged());
        }
        return !Objects.equals(row.flagNote(), verdict.flagNote());
    }
}
