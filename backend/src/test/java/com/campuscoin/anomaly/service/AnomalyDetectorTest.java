package com.campuscoin.anomaly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.anomaly.entity.AnomalyFlagType;
import com.campuscoin.anomaly.entity.CategoryAmountStats;
import com.campuscoin.anomaly.entity.FlaggedTransactionRow;
import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.common.setting.SettingReader;
import com.campuscoin.common.setting.entity.SystemSetting;
import com.campuscoin.common.setting.repository.SystemSettingRepository;

/**
 * UC-24's two findings, tested directly rather than through the API.
 *
 * <p><b>Why this is a unit test while the rest of the module is not.</b> A finding is a pure function of
 * the student's rows, their category totals and the two settings, so every case is expressible without
 * a database - and the cases that matter are the ones a seeded fixture finds hardest to produce. "A
 * record exactly at the multiple", "a category whose only record is the one being examined", "two
 * records of the same amount whose dates are exactly the window's width apart": each is a boundary, and
 * each is one line here and a careful sequence of dated transactions through the API otherwise. The
 * integration suite proves the wiring - that a verdict reaches {@code sp_flag_transaction} and comes
 * back on the list - and this file proves the arithmetic that decides what that verdict is.
 *
 * <p><b>Every fixture spaces its records so that date serves one purpose at a time.</b> Two records of
 * the same amount inside the window are a duplicate, so a test about the average has to place its
 * records further apart than the window, or it would be measuring both rules at once and asserting
 * something other than what it says. {@link #WINDOW_DAYS} is the spacing used for that.
 *
 * <p><b>The detector is reached through a real {@link SettingReader}</b> over a stubbed
 * {@link SystemSettingRepository}, so both lookups are exercised rather than bypassed and the defaults
 * asserted here are the ones the procedure seeds.
 */
class AnomalyDetectorTest {

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 1);

    private static final BigDecimal MULTIPLIER = AnomalyDetector.DEFAULT_UNUSUAL_MULTIPLIER;

    private static final int WINDOW_DAYS = AnomalyDetector.DEFAULT_DUPLICATE_WINDOW_DAYS;

    private static final long FOOD_CATEGORY_ID = 4L;

    private static final long TRANSPORT_CATEGORY_ID = 5L;

    private final AnomalyDetector detector = new AnomalyDetector(new SettingReader(seededSettings()));

    // ==================================================================
    //  Duplicates
    // ==================================================================

    @Test
    @DisplayName("UC-24: the same amount in the same category inside the window is a duplicate")
    void sameAmountSameCategoryInsideTheWindowIsADuplicate() {
        FlaggedTransactionRow first = record(1L, FOOD_CATEGORY_ID, "25.00", 0);
        FlaggedTransactionRow second = record(2L, FOOD_CATEGORY_ID, "25.00", 1);

        List<AnomalyDetector.Verdict> verdicts = detect(List.of(first, second));

        assertThat(verdicts.get(0).flagType()).isEqualTo(AnomalyFlagType.NONE);
        assertThat(verdicts.get(1).flagType()).isEqualTo(AnomalyFlagType.DUPLICATE);
        assertThat(verdicts.get(1).transactionId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("UC-24: the later record of a pair is the one reported, and the note names the earlier")
    void theLaterRecordOfAPairIsTheOneReported() {
        // Only one of the pair is flagged - the later by id - because flagging both would be two marks
        // and two history rows for one mistake. The record that reads as "the second time this was
        // entered" is the one the note describes, and the date it names is when the student entered
        // the first.
        FlaggedTransactionRow earlier = record(11L, FOOD_CATEGORY_ID, "12.50", 0);
        FlaggedTransactionRow later = record(12L, FOOD_CATEGORY_ID, "12.50", 2);

        List<AnomalyDetector.Verdict> verdicts = detect(List.of(earlier, later));

        assertThat(verdicts.get(0).flagType()).isEqualTo(AnomalyFlagType.NONE);
        assertThat(verdicts.get(1).flagType()).isEqualTo(AnomalyFlagType.DUPLICATE);
        assertThat(verdicts.get(1).flagNote()).contains(BASE_DATE.toString());
    }

    @Test
    @DisplayName("UC-24: the note names the nearest earlier twin, not the oldest one")
    void theNoteNamesTheNearestEarlierTwin() {
        // Three records of the same amount, a day apart. The last has two earlier twins to choose from
        // and the note has to name one of them deterministically - so it names the most recent, which
        // is the one the student is most likely to have confused with it. Naming the oldest would pass
        // a "the note mentions a date" assertion and send them to the wrong row.
        List<FlaggedTransactionRow> records = List.of(
                record(1L, FOOD_CATEGORY_ID, "30.00", 0),
                record(2L, FOOD_CATEGORY_ID, "30.00", 1),
                record(3L, FOOD_CATEGORY_ID, "30.00", 2));

        List<AnomalyDetector.Verdict> verdicts = detect(records);

        assertThat(verdicts.get(2).flagNote())
                .contains(BASE_DATE.plusDays(1).toString())
                .doesNotContain(BASE_DATE.plusDays(2).toString());
    }

    @Test
    @DisplayName("UC-24: the window is inclusive at both ends, and a day outside it is not a duplicate")
    void theWindowIsInclusiveAtBothEnds() {
        // Exactly at the boundary, then one day past it - the two cases a comparison written with the
        // wrong operator gets wrong.
        assertThat(verdictFor(record(1L, FOOD_CATEGORY_ID, "8.00", 0),
                record(2L, FOOD_CATEGORY_ID, "8.00", WINDOW_DAYS)))
                .isEqualTo(AnomalyFlagType.DUPLICATE);

        assertThat(verdictFor(record(1L, FOOD_CATEGORY_ID, "8.00", 0),
                record(2L, FOOD_CATEGORY_ID, "8.00", WINDOW_DAYS + 1)))
                .isEqualTo(AnomalyFlagType.NONE);
    }

    @Test
    @DisplayName("UC-24: the window is measured on the transaction's own date, in both directions")
    void theWindowIsMeasuredInBothDirections() {
        // The later row by id carries the earlier date: a record entered today about last week is still
        // a duplicate of one entered yesterday about the same day. Measuring the gap with a sign would
        // miss this, and would let a duplicate through whenever the student back-dated the second one.
        FlaggedTransactionRow enteredFirst = record(1L, FOOD_CATEGORY_ID, "30.00", 5);
        FlaggedTransactionRow enteredSecond = record(2L, FOOD_CATEGORY_ID, "30.00", 4);

        assertThat(verdictFor(enteredFirst, enteredSecond)).isEqualTo(AnomalyFlagType.DUPLICATE);
    }

    @Test
    @DisplayName("UC-24: a different amount, or a different category, is not a duplicate")
    void aDifferentAmountOrCategoryIsNotADuplicate() {
        assertThat(verdictFor(record(1L, FOOD_CATEGORY_ID, "25.00", 0),
                record(2L, FOOD_CATEGORY_ID, "25.01", 1)))
                .isEqualTo(AnomalyFlagType.NONE);

        assertThat(verdictFor(record(1L, FOOD_CATEGORY_ID, "25.00", 0),
                record(2L, TRANSPORT_CATEGORY_ID, "25.00", 1)))
                .isEqualTo(AnomalyFlagType.NONE);
    }

    @Test
    @DisplayName("UC-24: amounts are compared as decimals, not as objects")
    void amountsAreComparedAsDecimalsNotAsObjects() {
        // BigDecimal.equals treats 25.0 and 25.00 as different values, because it compares the scale as
        // well as the value. The column is DECIMAL(15,2), so every amount arrives at the same scale
        // today - but the rule is "the same amount", and a comparison that depended on how the driver
        // formatted a decimal would be the right rule holding by accident.
        FlaggedTransactionRow first = record(1L, FOOD_CATEGORY_ID, "25.00", 0);
        FlaggedTransactionRow second = record(2L, FOOD_CATEGORY_ID, "25.0", 1);

        assertThat(verdictFor(first, second)).isEqualTo(AnomalyFlagType.DUPLICATE);
    }

    @Test
    @DisplayName("UC-24: a chain of identical records flags each against its nearest earlier twin")
    void aChainOfIdenticalRecordsFlagsEachAgainstItsNearestEarlierTwin() {
        // Three records of the same amount. The first is the original; the second and third each
        // duplicate one that came before, so two are flagged and the original is not - a finding per
        // record rather than a finding per pair.
        FlaggedTransactionRow first = record(1L, FOOD_CATEGORY_ID, "9.99", 0);
        FlaggedTransactionRow second = record(2L, FOOD_CATEGORY_ID, "9.99", 1);
        FlaggedTransactionRow third = record(3L, FOOD_CATEGORY_ID, "9.99", 2);

        List<AnomalyDetector.Verdict> verdicts = detect(List.of(first, second, third));

        assertThat(verdicts).extracting(AnomalyDetector.Verdict::flagType)
                .containsExactly(AnomalyFlagType.NONE, AnomalyFlagType.DUPLICATE,
                        AnomalyFlagType.DUPLICATE);
    }

    @Test
    @DisplayName("UC-24: an exact amount match outside the window is two real purchases")
    void anExactAmountMatchOutsideTheWindowIsNotADuplicate() {
        // The same price a month apart is rent, or a subscription - not a double entry. This is the
        // case the window exists for.
        assertThat(verdictFor(record(1L, FOOD_CATEGORY_ID, "120.00", 0),
                record(2L, FOOD_CATEGORY_ID, "120.00", 30)))
                .isEqualTo(AnomalyFlagType.NONE);
    }

    // ==================================================================
    //  Unusual amounts
    // ==================================================================

    @Test
    @DisplayName("UC-24: an amount far above the student's own average is unusual")
    void anAmountFarAboveTheStudentsOwnAverageIsUnusual() {
        // Four ordinary lunches of 10.00 and one of 90.00. The average of the others is 10.00, so the
        // threshold is 30.00 and 90.00 is well past it. The four are spaced beyond the window so that
        // this test measures the average and not the duplicate rule.
        List<FlaggedTransactionRow> records = List.of(
                record(1L, FOOD_CATEGORY_ID, "10.00", 0),
                record(2L, FOOD_CATEGORY_ID, "10.00", 10),
                record(3L, FOOD_CATEGORY_ID, "10.00", 20),
                record(4L, FOOD_CATEGORY_ID, "10.00", 30),
                record(5L, FOOD_CATEGORY_ID, "90.00", 40));

        List<AnomalyDetector.Verdict> verdicts = detect(records);

        assertThat(verdicts.get(4).flagType()).isEqualTo(AnomalyFlagType.UNUSUAL_AMOUNT);
        assertThat(verdicts.get(4).flagNote()).contains("90.00").contains("10.00");
        assertThat(verdicts.subList(0, 4))
                .extracting(AnomalyDetector.Verdict::flagType)
                .containsOnly(AnomalyFlagType.NONE);
    }

    @Test
    @DisplayName("UC-24: the record is excluded from the average it is measured against")
    void theRecordIsExcludedFromItsOwnBaseline() {
        // The substance of the rule, in the smallest case that shows it: one ordinary record and one
        // large one. Including the large record in the baseline would give an average of 55.00 and a
        // threshold of 165.00, so 100.00 would pass unremarked - the check would fail exactly where it
        // is most obviously right.
        List<FlaggedTransactionRow> records = List.of(
                record(1L, FOOD_CATEGORY_ID, "10.00", 0),
                record(2L, FOOD_CATEGORY_ID, "100.00", 40));

        List<AnomalyDetector.Verdict> verdicts = detect(records);

        assertThat(verdicts.get(0).flagType()).isEqualTo(AnomalyFlagType.NONE);
        assertThat(verdicts.get(1).flagType()).isEqualTo(AnomalyFlagType.UNUSUAL_AMOUNT);
        assertThat(verdicts.get(1).flagNote()).contains("10.00").contains("1 record");
    }

    @Test
    @DisplayName("UC-24: exactly at the multiple is unusual, and just under it is not")
    void exactlyAtTheMultipleIsUnusual() {
        // "At least three times", which is how the setting reads. Both sides of the boundary: the
        // record being examined is excluded from the baseline, so 10.00 and 10.00 give an average of
        // 10.00 and a threshold of 30.00 either way.
        List<FlaggedTransactionRow> atTheBoundary = List.of(
                record(1L, FOOD_CATEGORY_ID, "10.00", 0),
                record(2L, FOOD_CATEGORY_ID, "10.00", 10),
                record(3L, FOOD_CATEGORY_ID, "30.00", 20));

        assertThat(detect(atTheBoundary).get(2).flagType())
                .isEqualTo(AnomalyFlagType.UNUSUAL_AMOUNT);

        List<FlaggedTransactionRow> justUnder = List.of(
                record(1L, FOOD_CATEGORY_ID, "10.00", 0),
                record(2L, FOOD_CATEGORY_ID, "10.00", 10),
                record(3L, FOOD_CATEGORY_ID, "29.99", 20));

        assertThat(detect(justUnder).get(2).flagType())
                .isEqualTo(AnomalyFlagType.NONE);
    }

    @Test
    @DisplayName("UC-24: the student's only record in a category has no baseline and is not unusual")
    void theOnlyRecordInACategoryIsNotUnusual() {
        // One record is a figure, not a habit. If this were flagged, every new category a student used
        // would arrive pre-marked and the mark would mean nothing.
        List<FlaggedTransactionRow> records = List.of(record(1L, FOOD_CATEGORY_ID, "500.00", 0));

        assertThat(detect(records).get(0).flagType()).isEqualTo(AnomalyFlagType.NONE);
    }

    @Test
    @DisplayName("UC-24: one other record is enough to make a baseline")
    void oneOtherRecordIsEnoughToMakeABaseline() {
        // The other side of the count guard: the exclusion is "no other record", not "fewer than three
        // records". The note says how many records the average came from, because an average over one
        // is a far weaker claim than one over twelve and the student is the only one who can tell.
        List<FlaggedTransactionRow> ordinary = List.of(
                record(1L, FOOD_CATEGORY_ID, "100.00", 0),
                record(2L, FOOD_CATEGORY_ID, "12.50", 40));

        assertThat(detect(ordinary).get(1).flagType()).isEqualTo(AnomalyFlagType.NONE);

        List<FlaggedTransactionRow> unusuallyLarge = List.of(
                record(1L, FOOD_CATEGORY_ID, "2.00", 0),
                record(2L, FOOD_CATEGORY_ID, "12.50", 40));

        assertThat(detect(unusuallyLarge).get(1).flagType())
                .isEqualTo(AnomalyFlagType.UNUSUAL_AMOUNT);
    }

    @Test
    @DisplayName("UC-24: each category is measured against its own average")
    void eachCategoryIsMeasuredAgainstItsOwnAverage() {
        // 50.00 is unremarkable in a category where the student spends around that, and unusual in one
        // where they spend a couple of units. One student-wide average would call neither unusual, and
        // the feature is about where this student is unusual.
        List<FlaggedTransactionRow> records = List.of(
                record(1L, FOOD_CATEGORY_ID, "40.00", 0),
                record(2L, FOOD_CATEGORY_ID, "40.00", 20),
                record(3L, FOOD_CATEGORY_ID, "50.00", 40),
                record(4L, TRANSPORT_CATEGORY_ID, "2.00", 3),
                record(5L, TRANSPORT_CATEGORY_ID, "3.00", 4),
                record(6L, TRANSPORT_CATEGORY_ID, "50.00", 5));

        List<AnomalyDetector.Verdict> verdicts = detect(records);

        assertThat(verdicts.get(2).flagType()).isEqualTo(AnomalyFlagType.NONE);
        assertThat(verdicts.get(5).flagType()).isEqualTo(AnomalyFlagType.UNUSUAL_AMOUNT);
    }

    @Test
    @DisplayName("UC-24: a record with no category totals at all is not unusual")
    void aRecordWithNoCategoryTotalsIsNotUnusual() {
        // The other way a baseline can be missing: the totals are read by a second query, and a
        // category the row belongs to but the map does not cover has nothing to compare against. The
        // guard is "no baseline", however the absence arises.
        assertThat(detector.detect(List.of(record(1L, FOOD_CATEGORY_ID, "500.00", 0)),
                        Map.of(), WINDOW_DAYS, MULTIPLIER)
                .get(0).flagType())
                .isEqualTo(AnomalyFlagType.NONE);
    }

    // ==================================================================
    //  Verdicts and the stored comparison
    // ==================================================================

    @Test
    @DisplayName("UC-24: every examined record gets a verdict, including the unremarkable ones")
    void everyExaminedRecordGetsAVerdict() {
        // The reason NONE is a member of the enum rather than an absence: a record that was flagged and
        // has since been corrected has to be answered NONE, or the flag could never be cleared.
        List<FlaggedTransactionRow> records = List.of(
                record(1L, FOOD_CATEGORY_ID, "25.00", 0),
                record(2L, FOOD_CATEGORY_ID, "25.00", 1),
                record(3L, FOOD_CATEGORY_ID, "25.00", 30));

        List<AnomalyDetector.Verdict> verdicts = detect(records);

        assertThat(verdicts).hasSize(3);
        assertThat(verdicts).extracting(AnomalyDetector.Verdict::transactionId)
                .containsExactly(1L, 2L, 3L);
        assertThat(verdicts.get(0).flagType()).isEqualTo(AnomalyFlagType.NONE);
        assertThat(verdicts.get(0).flagNote()).isNull();
        assertThat(verdicts.get(2).flagType()).isEqualTo(AnomalyFlagType.NONE);
    }

    @Test
    @DisplayName("UC-24: the verdict depends only on the rows and the settings, so a scan is repeatable")
    void theVerdictIsAFunctionOfTheRowsAndTheSettings() {
        // Nothing in a verdict carries a timestamp or an identifier of the scan that produced it, which
        // is what lets the service compare it against the stored note and skip the write.
        List<FlaggedTransactionRow> records = List.of(
                record(1L, FOOD_CATEGORY_ID, "25.00", 0),
                record(2L, FOOD_CATEGORY_ID, "25.00", 1),
                record(3L, FOOD_CATEGORY_ID, "900.00", 20));

        assertThat(detect(records)).isEqualTo(detect(records));
    }

    @Test
    @DisplayName("UC-24: a record that already carries the found verdict is left alone")
    void aRecordThatAlreadyCarriesTheVerdictIsLeftAlone() {
        // The row is fed back as it would be stored - flagged, with the detector's own sentence in the
        // note - and the comparison has to see no difference, or a rescan would rewrite every
        // already-correct record and append a history row for each.
        AnomalyDetector.Verdict produced = detect(List.of(
                record(1L, FOOD_CATEGORY_ID, "25.00", 0),
                record(2L, FOOD_CATEGORY_ID, "25.00", 1))).get(1);

        FlaggedTransactionRow asStored = flagged(2L, FOOD_CATEGORY_ID, "25.00", 1,
                produced.flagType(), produced.flagNote());

        assertThat(AnomalyDetector.differsFromStored(asStored, produced)).isFalse();
    }

    @Test
    @DisplayName("UC-24: a retuned threshold changes the note, so the record is rewritten")
    void aRetunedThresholdChangesTheNote() {
        // The stored note describes the rule that produced it. If a deployment setting changed and the
        // sentence would differ, the record is rewritten even though the type has not moved - otherwise
        // the student reads an explanation of a rule that is no longer running.
        AnomalyDetector.Verdict produced = detect(List.of(
                record(1L, FOOD_CATEGORY_ID, "25.00", 0),
                record(2L, FOOD_CATEGORY_ID, "25.00", 1))).get(1);

        FlaggedTransactionRow storedWithAnOlderSentence = flagged(2L, FOOD_CATEGORY_ID, "25.00", 1,
                AnomalyFlagType.DUPLICATE,
                "This looks like a record you already entered: the same amount in Food on "
                        + BASE_DATE.minusDays(7) + ". Check whether it was recorded twice.");

        assertThat(AnomalyDetector.differsFromStored(storedWithAnOlderSentence, produced)).isTrue();
    }

    @Test
    @DisplayName("UC-24: a corrected record is answered NONE, and that counts as a difference")
    void aCorrectedRecordIsAnsweredNoneAndCountsAsADifference() {
        // The record was a duplicate, so it is flagged; its twin has since been put in the trash, so
        // the scan no longer sees it. The verdict is NONE, and writing it is what clears the stored
        // flag - the only way a flag is ever removed.
        FlaggedTransactionRow stillFlagged = flagged(1L, FOOD_CATEGORY_ID, "25.00", 0,
                AnomalyFlagType.DUPLICATE,
                "This looks like a record you already entered: the same amount in Food on "
                        + BASE_DATE + ". Check whether it was recorded twice.");

        List<AnomalyDetector.Verdict> verdicts = detect(List.of(stillFlagged));

        assertThat(verdicts.get(0).flagType()).isEqualTo(AnomalyFlagType.NONE);
        assertThat(AnomalyDetector.differsFromStored(stillFlagged, verdicts.get(0))).isTrue();
    }

    @Test
    @DisplayName("UC-24: a note that outlived its clearing is rewritten")
    void aNoteThatOutlivedItsClearingIsRewritten() {
        // sp_flag_transaction nulls the note when it clears a flag, so this shape should not exist - but
        // an unflagged row carrying a note is what a hand-run UPDATE could leave, and the scan is what
        // puts it right.
        FlaggedTransactionRow inconsistent = flagged(1L, FOOD_CATEGORY_ID, "25.00", 0,
                AnomalyFlagType.NONE, "This looks like a record you already entered.");

        AnomalyDetector.Verdict verdict = detect(List.of(inconsistent)).get(0);

        assertThat(verdict.flagType()).isEqualTo(AnomalyFlagType.NONE);
        assertThat(AnomalyDetector.differsFromStored(inconsistent, verdict)).isTrue();
    }

    @Test
    @DisplayName("UC-24: an unflagged row whose flagged boolean is set is rewritten")
    void anUnflaggedRowWithAStaleBooleanIsRewritten() {
        // The third column of the stored triple. A row with flag_type NONE and no note but
        // is_flagged = 1 would be returned by the list - which filters on is_flagged - and explained by
        // nothing. The comparison looks at the boolean as well as the type for exactly this shape.
        FlaggedTransactionRow inconsistent = new FlaggedTransactionRow(
                1L, FOOD_CATEGORY_ID, "Food", CategoryType.EXPENSE, new BigDecimal("25.00"),
                BASE_DATE, null, Boolean.TRUE, AnomalyFlagType.NONE, null);

        AnomalyDetector.Verdict verdict = detect(List.of(inconsistent)).get(0);

        assertThat(verdict.flagType()).isEqualTo(AnomalyFlagType.NONE);
        assertThat(AnomalyDetector.differsFromStored(inconsistent, verdict)).isTrue();
    }

    @Test
    @DisplayName("UC-24: the note for the longest name the column allows still fits the note column")
    void theNoteFitsForTheLongestNameTheColumnAllows() {
        // categories.name is VARCHAR(80) and flag_note is VARCHAR(255), so the formatted sentence and
        // the longest permitted name have to fit together - otherwise MySQL would truncate the note and
        // the student would be shown a sentence that stops mid-word. This asserts the formatted note,
        // by name, is the one that is used.
        String longestName = "N".repeat(80);

        AnomalyDetector.Verdict duplicateVerdict = detect(List.of(
                withCategoryName(record(1L, FOOD_CATEGORY_ID, "25.00", 0), longestName),
                withCategoryName(record(2L, FOOD_CATEGORY_ID, "25.00", 1), longestName))).get(1);

        AnomalyDetector.Verdict unusualVerdict = detect(List.of(
                withCategoryName(record(1L, FOOD_CATEGORY_ID, "10.00", 0), longestName),
                withCategoryName(record(2L, FOOD_CATEGORY_ID, "900.00", 40), longestName))).get(1);

        assertThat(duplicateVerdict.flagNote()).contains(longestName);
        assertThat(unusualVerdict.flagNote()).contains(longestName);
        assertThat(length(duplicateVerdict.flagNote()))
                .isLessThanOrEqualTo(AnomalyDetector.MAX_NOTE_LENGTH);
        assertThat(length(unusualVerdict.flagNote()))
                .isLessThanOrEqualTo(AnomalyDetector.MAX_NOTE_LENGTH);
    }

    @Test
    @DisplayName("UC-24: a note that would not fit the column falls back to a shorter sentence")
    void aNoteThatWouldNotFitFallsBackToAShorterSentence() {
        // Names this long cannot come from the schema, so the fallback is defence in depth rather than
        // a path real data takes - but it is the guard that keeps a widened column from producing a
        // truncated sentence, and dead guards are exactly what rots. The assertion is that the name is
        // dropped, not that any particular wording replaced it.
        String impossibleName = "N".repeat(400);

        AnomalyDetector.Verdict duplicateVerdict = detect(List.of(
                withCategoryName(record(1L, FOOD_CATEGORY_ID, "25.00", 0), impossibleName),
                withCategoryName(record(2L, FOOD_CATEGORY_ID, "25.00", 1), impossibleName))).get(1);

        AnomalyDetector.Verdict unusualVerdict = detect(List.of(
                withCategoryName(record(1L, FOOD_CATEGORY_ID, "10.00", 0), impossibleName),
                withCategoryName(record(2L, FOOD_CATEGORY_ID, "900.00", 40), impossibleName))).get(1);

        for (AnomalyDetector.Verdict verdict : List.of(duplicateVerdict, unusualVerdict)) {
            assertThat(verdict.flagType()).isNotEqualTo(AnomalyFlagType.NONE);
            assertThat(verdict.flagNote()).doesNotContain(impossibleName);
            assertThat(length(verdict.flagNote()))
                    .isLessThanOrEqualTo(AnomalyDetector.MAX_NOTE_LENGTH);
        }
    }

    @Test
    @DisplayName("UC-24: an empty history yields no verdicts rather than an error")
    void anEmptyHistoryYieldsNoVerdicts() {
        assertThat(detect(List.of())).isEmpty();
    }

    // ==================================================================
    //  The two settings
    // ==================================================================

    @Test
    @DisplayName("UC-24: both thresholds are read from the settings table")
    void bothThresholdsComeFromSettings() {
        assertThat(detector.duplicateWindowDays()).isEqualTo(WINDOW_DAYS);
        assertThat(detector.unusualMultiplier()).isEqualByComparingTo(new BigDecimal("3"));
    }

    @Test
    @DisplayName("UC-24: a retuned window is obeyed rather than the compiled default")
    void aRetunedWindowIsObeyed() {
        // VĐ-05: an administrator retunes a threshold without a redeploy. The window is read at the
        // moment it is used, so a window of ten days makes a five-day gap a duplicate - which the
        // compiled default of three would have refused.
        SettingReader retunedSettings = new SettingReader(settingsRepository(
                SettingReader.ANOMALY_DUPLICATE_WINDOW_DAYS, "10",
                SettingReader.ANOMALY_UNUSUAL_MULTIPLIER, "2.5"));
        AnomalyDetector retuned = new AnomalyDetector(retunedSettings);

        assertThat(retuned.duplicateWindowDays()).isEqualTo(10);
        assertThat(retuned.unusualMultiplier()).isEqualByComparingTo(new BigDecimal("2.5"));

        List<FlaggedTransactionRow> records = List.of(
                record(1L, FOOD_CATEGORY_ID, "8.00", 0),
                record(2L, FOOD_CATEGORY_ID, "8.00", 5));

        assertThat(retuned.detect(records, categoryStatsOf(records),
                retuned.duplicateWindowDays(), retuned.unusualMultiplier())
                .get(1).flagType())
                .isEqualTo(AnomalyFlagType.DUPLICATE);
    }

    @Test
    @DisplayName("UC-24: a threshold that would disable the check falls back to the default")
    void anUnusableThresholdFallsBackToTheDefault() {
        // A zero or negative multiplier would make every record unusual, and a zero-day window would
        // make the duplicate check useless - so an unusable value is treated as absent rather than
        // obeyed, which is the policy SettingReader applies to every threshold it reads.
        AnomalyDetector fromUnusableValues = new AnomalyDetector(new SettingReader(settingsRepository(
                SettingReader.ANOMALY_DUPLICATE_WINDOW_DAYS, "-1",
                SettingReader.ANOMALY_UNUSUAL_MULTIPLIER, "not a number")));

        assertThat(fromUnusableValues.duplicateWindowDays())
                .isEqualTo(AnomalyDetector.DEFAULT_DUPLICATE_WINDOW_DAYS);
        assertThat(fromUnusableValues.unusualMultiplier())
                .isEqualByComparingTo(AnomalyDetector.DEFAULT_UNUSUAL_MULTIPLIER);
    }

    // ==================================================================
    //  Fixtures
    // ==================================================================

    private List<AnomalyDetector.Verdict> detect(List<FlaggedTransactionRow> rows) {
        return detector.detect(rows, categoryStatsOf(rows), WINDOW_DAYS, MULTIPLIER);
    }

    private AnomalyFlagType verdictFor(FlaggedTransactionRow first, FlaggedTransactionRow second) {
        return detect(List.of(first, second)).get(1).flagType();
    }

    private static int length(String note) {
        return note == null ? 0 : note.length();
    }

    /** Seeds the repository with the two values {@code db/05_seed.sql} writes. */
    private static SystemSettingRepository seededSettings() {
        return settingsRepository(
                SettingReader.ANOMALY_DUPLICATE_WINDOW_DAYS, "3",
                SettingReader.ANOMALY_UNUSUAL_MULTIPLIER, "3");
    }

    private static SystemSettingRepository settingsRepository(String firstKey, String firstValue,
                                                              String secondKey, String secondValue) {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        stub(repository, firstKey, firstValue);
        stub(repository, secondKey, secondValue);
        return repository;
    }

    private static void stub(SystemSettingRepository repository, String key, String value) {
        SystemSetting setting = mock(SystemSetting.class);
        when(setting.getSettingValue()).thenReturn(value);
        when(repository.findBySettingKey(key)).thenReturn(Optional.of(setting));
    }

    /**
     * The category totals the detector would read, derived from the rows the same way
     * {@code AnomalyViewDao} derives them - so the fixture cannot disagree with the query about what
     * "the student's average" is.
     */
    private static Map<Long, CategoryAmountStats> categoryStatsOf(List<FlaggedTransactionRow> rows) {
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        Map<Long, Long> counts = new LinkedHashMap<>();

        for (FlaggedTransactionRow row : rows) {
            totals.merge(row.categoryId(), row.amount(), BigDecimal::add);
            counts.merge(row.categoryId(), 1L, Long::sum);
        }

        Map<Long, CategoryAmountStats> stats = new LinkedHashMap<>();
        totals.forEach((categoryId, total) -> stats.put(categoryId,
                new CategoryAmountStats(categoryId, counts.get(categoryId), total)));
        return stats;
    }

    private static FlaggedTransactionRow record(Long id, Long categoryId, String amount,
                                                int daysAfterBase) {
        return flagged(id, categoryId, amount, daysAfterBase, AnomalyFlagType.NONE, null);
    }

    private static FlaggedTransactionRow flagged(Long id, Long categoryId, String amount,
                                                 int daysAfterBase, AnomalyFlagType flagType,
                                                 String flagNote) {
        return new FlaggedTransactionRow(
                id,
                categoryId,
                categoryId == FOOD_CATEGORY_ID ? "Food" : "Transport",
                CategoryType.EXPENSE,
                new BigDecimal(amount),
                BASE_DATE.plusDays(daysAfterBase),
                null,
                flagType != AnomalyFlagType.NONE,
                flagType,
                flagNote);
    }

    private static FlaggedTransactionRow withCategoryName(FlaggedTransactionRow row, String name) {
        return new FlaggedTransactionRow(row.transactionId(), row.categoryId(), name,
                row.categoryType(), row.amount(), row.txnDate(), row.encryptedDescription(),
                row.isFlagged(), row.flagType(), row.flagNote());
    }
}
