package com.campuscoin.imports.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.campuscoin.common.setting.SettingReader;

/**
 * Decides which imported rows the student appears to have recorded already (UC-11).
 *
 * <p><b>The rule is {@code AnomalyDetector}'s, reused rather than restated.</b> UC-24 defines what a
 * duplicate is for this project: the same category, the same amount, and a date within
 * {@code anomaly.duplicate_window_days} days either side, compared only against <em>earlier</em>
 * records. Nothing in UC-11's documentation defines a second meaning, and inventing one would leave the
 * project with two answers to "is this a duplicate?" - so the same setting, the same window, the same
 * direction of comparison and the same nearest-earlier-twin choice are applied here. A student who has
 * a record flagged by UC-24 and imports a row that matches it is told the same thing by both.
 *
 * <p><b>What is compared against what, stated exactly, because this is where a mistake would silently
 * drop a student's data.</b> A row is a duplicate when an <em>earlier</em> record exists that matches
 * it, where "earlier" means one of two things:
 *
 * <ul>
 *   <li><b>A record already in the database</b> - the student's own live transactions, in id order, so
 *       importing a file twice imports nothing the second time rather than doubling every figure.</li>
 *   <li><b>An earlier row of the same file</b> - so a file listing one purchase on two consecutive
 *       lines is reported rather than imported twice.</li>
 * </ul>
 *
 * <p><b>Duplicates are not imported, and that is the whole reason this class exists.</b>
 * {@code sp_apply_csv_batch} imports exactly the rows left in {@code VALID}, so marking a row
 * {@code DUPLICATE} is the mechanism by which it is excluded. A false positive therefore loses a record
 * the student did have, which is why every rule here is a conjunction: the amount must be equal under
 * {@code compareTo}, the category must be the same identified category, and only then is the date
 * window consulted. The detector errs toward not flagging - a row whose category cannot be resolved to
 * an id is never a duplicate, because there is nothing to compare it against.
 *
 * <p><b>The category is compared by id, which means the row's category has to be resolved before this
 * runs.</b> That resolution is {@code ImportCategoryResolver}'s job and mirrors
 * {@code sp_apply_csv_batch}'s Case B. It is <em>not</em> written to {@code resolved_category_id}: the
 * procedure still decides the real category at the commit, and writing a preview-time guess into the
 * column the procedure treats as the student's own authoritative choice would turn a fallback into a
 * decision.
 *
 * <p>No repository and no transaction: it takes rows and returns findings, so a test pins the rule with
 * plain lists - the shape {@code AnomalyDetector} has, and for the same reason.
 */
@Component
public class ImportDuplicateDetector {

    /** UC-24's seeded default for {@code anomaly.duplicate_window_days}, used when it is unusable. */
    static final int DEFAULT_DUPLICATE_WINDOW_DAYS = 3;

    /**
     * The width of {@code import_rows.error_message}, which is where a duplicate's explanation goes.
     *
     * <p>The column is named for an error, and a duplicate is not an error in the row - the row is fine,
     * it is the record that is already there. It is nevertheless the only free-text column on the row,
     * and UC-11 requires an invalid <em>or excluded</em> row to be identifiable on the preview screen, so
     * the explanation lives here and the row's own {@code row_status} says which of the two it is.
     */
    static final int MAX_NOTE_LENGTH = 255;

    private final SettingReader settingReader;

    public ImportDuplicateDetector(SettingReader settingReader) {
        this.settingReader = settingReader;
    }

    /**
     * One record to compare: a row of the file, or one the student already has.
     *
     * <p>{@code categoryId} may be null, which means "this row's category could not be resolved" - and
     * a null never matches anything, including another null. Treating two unresolved rows as duplicates
     * of each other would flag rows on the strength of an amount and a date alone, which is exactly the
     * false positive that loses a student's data.
     */
    public record Candidate(int lineNumber, Long categoryId, BigDecimal amount, LocalDate txnDate) {
    }

    /**
     * One row the student appears to have recorded already, and the earlier date it matches.
     *
     * <p>The note names the earlier date so the preview can say which record to look for, which is the
     * same thing UC-24's own note does - and it is produced by this class so the two wordings cannot
     * drift apart.
     */
    public record Finding(int lineNumber, LocalDate earlierDate, String note) {
    }

    /** UC-11: the window, read from UC-24's setting so the two features cannot disagree about it. */
    int duplicateWindowDays() {
        return settingReader.getInt(SettingReader.ANOMALY_DUPLICATE_WINDOW_DAYS,
                DEFAULT_DUPLICATE_WINDOW_DAYS);
    }

    /**
     * UC-11: which of the file's rows the student appears to have recorded already.
     *
     * @param existing the student's own live records, in id order - the order is what makes "earlier"
     *                 mean "older" and keeps the finding reproducible
     * @param fileRows the file's valid rows, in file order, with their categories already resolved
     * @return one finding per duplicate row, in file order; a row with no finding is importable
     */
    public List<Finding> detect(List<Candidate> existing, List<Candidate> fileRows) {
        int windowDays = duplicateWindowDays();

        // One list, database records first. A row of the file is therefore compared against every
        // record the student has and against every earlier row of the same file, in one pass, with one
        // definition of "earlier" - rather than two loops that could disagree about the window.
        List<Candidate> earlier = new ArrayList<>(existing.size() + fileRows.size());
        earlier.addAll(existing);

        List<Finding> findings = new ArrayList<>();

        for (Candidate row : fileRows) {
            Candidate twin = earlierDuplicateOf(row, earlier, windowDays);
            if (twin != null) {
                findings.add(new Finding(row.lineNumber(), twin.txnDate(),
                        duplicateNote(row, twin)));
            }
            // A duplicate row is still appended: it is itself the earlier record that a later identical
            // line should be matched against, which is what makes a file listing one purchase three
            // times report two duplicates rather than one - the same chaining AnomalyDetector gets from
            // walking the whole list.
            earlier.add(row);
        }

        return findings;
    }

    /**
     * The nearest earlier record this row duplicates, or null when it duplicates nothing.
     *
     * <p>Walking backwards and returning the first match gives the <em>nearest</em> earlier twin, so the
     * date the note names is the most recent time the student entered this rather than the oldest -
     * {@code AnomalyDetector#earlierDuplicateOf} records the same choice for the same reason.
     *
     * <p>{@code compareTo} rather than {@code equals} on the amount: the column is {@code DECIMAL(15,2)}
     * and a value read back and a value parsed from a file need not carry the same scale even when they
     * are the same number.
     *
     * <p>A null category on either side is not a match. See {@link Candidate}.
     */
    private static Candidate earlierDuplicateOf(Candidate row, List<Candidate> earlier, int windowDays) {
        for (int index = earlier.size() - 1; index >= 0; index--) {
            Candidate other = earlier.get(index);

            if (other.categoryId() == null || row.categoryId() == null
                    || !other.categoryId().equals(row.categoryId())) {
                continue;
            }
            if (other.amount() == null || row.amount() == null
                    || other.amount().compareTo(row.amount()) != 0) {
                continue;
            }
            if (other.txnDate() == null || row.txnDate() == null
                    || Math.abs(ChronoUnit.DAYS.between(other.txnDate(), row.txnDate())) > windowDays) {
                continue;
            }
            return other;
        }

        return null;
    }

    /**
     * Why a row is treated as already recorded.
     *
     * <p>Shorter than UC-24's own sentence, because this one is filed under a row number in a preview
     * list rather than shown beside a single record - so it names the date and the amount and leaves the
     * category to the row's own columns, which the student can see. Falls back to a generic sentence when
     * the formatted one would not fit {@code import_rows.error_message}.
     */
    private static String duplicateNote(Candidate row, Candidate twin) {
        String note = "You already have a record of " + row.amount().toPlainString() + " in this "
                + "category on " + twin.txnDate() + ". This row was not imported.";

        return note.length() <= MAX_NOTE_LENGTH
                ? note
                : "You already have a record of this amount in this category. This row was not "
                        + "imported.";
    }

    /** Whether two findings describe the same row, so a re-detection can be compared. */
    static boolean sameFinding(Finding first, Finding second) {
        return first != null && second != null
                && first.lineNumber() == second.lineNumber()
                && Objects.equals(first.earlierDate(), second.earlierDate());
    }
}
