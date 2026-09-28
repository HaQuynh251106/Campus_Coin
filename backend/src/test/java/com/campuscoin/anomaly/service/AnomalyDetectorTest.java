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

class AnomalyDetectorTest {

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 1);

    private static final BigDecimal MULTIPLIER = AnomalyDetector.DEFAULT_UNUSUAL_MULTIPLIER;

    private static final int WINDOW_DAYS = AnomalyDetector.DEFAULT_DUPLICATE_WINDOW_DAYS;

    private static final long FOOD_CATEGORY_ID = 4L;

    private static final long TRANSPORT_CATEGORY_ID = 5L;

    private final AnomalyDetector detector = new AnomalyDetector(new SettingReader(seededSettings()));

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

        FlaggedTransactionRow first = record(1L, FOOD_CATEGORY_ID, "25.00", 0);
        FlaggedTransactionRow second = record(2L, FOOD_CATEGORY_ID, "25.0", 1);

        assertThat(verdictFor(first, second)).isEqualTo(AnomalyFlagType.DUPLICATE);
    }

    @Test
    @DisplayName("UC-24: a chain of identical records flags each against its nearest earlier twin")
    void aChainOfIdenticalRecordsFlagsEachAgainstItsNearestEarlierTwin() {

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

        assertThat(verdictFor(record(1L, FOOD_CATEGORY_ID, "120.00", 0),
                record(2L, FOOD_CATEGORY_ID, "120.00", 30)))
                .isEqualTo(AnomalyFlagType.NONE);
    }

    @Test
    @DisplayName("UC-24: an amount far above the student's own average is unusual")
    void anAmountFarAboveTheStudentsOwnAverageIsUnusual() {

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

        List<FlaggedTransactionRow> records = List.of(record(1L, FOOD_CATEGORY_ID, "500.00", 0));

        assertThat(detect(records).get(0).flagType()).isEqualTo(AnomalyFlagType.NONE);
    }

    @Test
    @DisplayName("UC-24: one other record is enough to make a baseline")
    void oneOtherRecordIsEnoughToMakeABaseline() {

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

        assertThat(detector.detect(List.of(record(1L, FOOD_CATEGORY_ID, "500.00", 0)),
                        Map.of(), WINDOW_DAYS, MULTIPLIER)
                .get(0).flagType())
                .isEqualTo(AnomalyFlagType.NONE);
    }

    @Test
    @DisplayName("UC-24: every examined record gets a verdict, including the unremarkable ones")
    void everyExaminedRecordGetsAVerdict() {

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

        List<FlaggedTransactionRow> records = List.of(
                record(1L, FOOD_CATEGORY_ID, "25.00", 0),
                record(2L, FOOD_CATEGORY_ID, "25.00", 1),
                record(3L, FOOD_CATEGORY_ID, "900.00", 20));

        assertThat(detect(records)).isEqualTo(detect(records));
    }

    @Test
    @DisplayName("UC-24: a record that already carries the found verdict is left alone")
    void aRecordThatAlreadyCarriesTheVerdictIsLeftAlone() {

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

        FlaggedTransactionRow inconsistent = flagged(1L, FOOD_CATEGORY_ID, "25.00", 0,
                AnomalyFlagType.NONE, "This looks like a record you already entered.");

        AnomalyDetector.Verdict verdict = detect(List.of(inconsistent)).get(0);

        assertThat(verdict.flagType()).isEqualTo(AnomalyFlagType.NONE);
        assertThat(AnomalyDetector.differsFromStored(inconsistent, verdict)).isTrue();
    }

    @Test
    @DisplayName("UC-24: an unflagged row whose flagged boolean is set is rewritten")
    void anUnflaggedRowWithAStaleBooleanIsRewritten() {

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

    @Test
    @DisplayName("UC-24: both thresholds are read from the settings table")
    void bothThresholdsComeFromSettings() {
        assertThat(detector.duplicateWindowDays()).isEqualTo(WINDOW_DAYS);
        assertThat(detector.unusualMultiplier()).isEqualByComparingTo(new BigDecimal("3"));
    }

    @Test
    @DisplayName("UC-24: a retuned window is obeyed rather than the compiled default")
    void aRetunedWindowIsObeyed() {

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

        AnomalyDetector fromUnusableValues = new AnomalyDetector(new SettingReader(settingsRepository(
                SettingReader.ANOMALY_DUPLICATE_WINDOW_DAYS, "-1",
                SettingReader.ANOMALY_UNUSUAL_MULTIPLIER, "not a number")));

        assertThat(fromUnusableValues.duplicateWindowDays())
                .isEqualTo(AnomalyDetector.DEFAULT_DUPLICATE_WINDOW_DAYS);
        assertThat(fromUnusableValues.unusualMultiplier())
                .isEqualByComparingTo(AnomalyDetector.DEFAULT_UNUSUAL_MULTIPLIER);
    }

    private List<AnomalyDetector.Verdict> detect(List<FlaggedTransactionRow> rows) {
        return detector.detect(rows, categoryStatsOf(rows), WINDOW_DAYS, MULTIPLIER);
    }

    private AnomalyFlagType verdictFor(FlaggedTransactionRow first, FlaggedTransactionRow second) {
        return detect(List.of(first, second)).get(1).flagType();
    }

    private static int length(String note) {
        return note == null ? 0 : note.length();
    }

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
