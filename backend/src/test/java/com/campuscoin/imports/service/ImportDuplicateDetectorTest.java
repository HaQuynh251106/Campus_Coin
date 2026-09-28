package com.campuscoin.imports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.common.setting.SettingReader;
import com.campuscoin.common.setting.entity.SystemSetting;
import com.campuscoin.common.setting.repository.SystemSettingRepository;
import com.campuscoin.imports.service.ImportDuplicateDetector.Candidate;
import com.campuscoin.imports.service.ImportDuplicateDetector.Finding;

class ImportDuplicateDetectorTest {

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 1);

    private static final int WINDOW_DAYS = ImportDuplicateDetector.DEFAULT_DUPLICATE_WINDOW_DAYS;

    private static final long FOOD = 4L;
    private static final long TRANSPORT = 5L;

    private final ImportDuplicateDetector detector =
            new ImportDuplicateDetector(new SettingReader(seededSetting()));

    @Test
    @DisplayName("UC-11: a file row matching an existing record is reported against that record's date")
    void aRowMatchingAnExistingRecordIsReported() {

        List<Finding> findings = detector.detect(
                List.of(existing(1, FOOD, "18.00", 0)),
                List.of(fileRow(2, FOOD, "18.00", 1)));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.lineNumber()).isEqualTo(2);
            assertThat(finding.earlierDate()).isEqualTo(BASE_DATE);
            assertThat(finding.note()).contains("18.00").contains(BASE_DATE.toString());
        });
    }

    @Test
    @DisplayName("UC-11: an earlier row of the same file is what a later identical line duplicates")
    void anEarlierRowOfTheSameFileIsTheTwin() {

        List<Finding> findings = detector.detect(
                List.of(),
                List.of(fileRow(2, FOOD, "25.00", 0), fileRow(3, FOOD, "25.00", 0)));

        assertThat(findings).singleElement()
                .extracting(Finding::lineNumber).isEqualTo(3);
        assertThat(findings.get(0).note()).contains(BASE_DATE.toString());
    }

    @Test
    @DisplayName("UC-11: a purchase listed three times yields two findings, not one")
    void chainingReportsEveryRepeatedLine() {

        List<Finding> findings = detector.detect(
                List.of(),
                List.of(fileRow(2, FOOD, "9.99", 0), fileRow(3, FOOD, "9.99", 0), fileRow(4, FOOD, "9.99", 0)));

        assertThat(findings).extracting(Finding::lineNumber).containsExactly(3, 4);
    }

    @Test
    @DisplayName("UC-11: the note names the nearest earlier twin, not the oldest one")
    void theNoteNamesTheNearestEarlierTwin() {

        List<Finding> findings = detector.detect(
                List.of(existing(1, FOOD, "30.00", 0), existing(2, FOOD, "30.00", 1)),
                List.of(fileRow(2, FOOD, "30.00", 2)));

        assertThat(findings.get(0).earlierDate()).isEqualTo(BASE_DATE.plusDays(1));
        assertThat(findings.get(0).note())
                .contains(BASE_DATE.plusDays(1).toString())
                .doesNotContain(BASE_DATE.plusDays(2).toString());
    }

    @Test
    @DisplayName("UC-11: the same amount in a different category is not a duplicate")
    void aDifferentCategoryIsNotADuplicate() {

        assertThat(detector.detect(
                List.of(existing(1, FOOD, "20.00", 0)),
                List.of(fileRow(2, TRANSPORT, "20.00", 0))))
                .isEmpty();
    }

    @Test
    @DisplayName("UC-11: a different amount is not a duplicate, however close")
    void aDifferentAmountIsNotADuplicate() {
        assertThat(detector.detect(
                List.of(existing(1, FOOD, "20.00", 0)),
                List.of(fileRow(2, FOOD, "20.01", 0))))
                .isEmpty();
    }

    @Test
    @DisplayName("UC-11: the same number at a different scale is still a duplicate")
    void aDifferentScaleIsTheSameNumber() {

        assertThat(detector.detect(
                List.of(existing(1, FOOD, "18.00", 0)),
                List.of(fileRow(2, FOOD, "18.0", 0))))
                .singleElement();
    }

    @Test
    @DisplayName("UC-11: a date exactly the window's width away is a duplicate, one day more is not")
    void theWindowIsInclusiveAtItsEdge() {

        assertThat(detector.detect(
                List.of(existing(1, FOOD, "15.00", 0)),
                List.of(fileRow(2, FOOD, "15.00", WINDOW_DAYS))))
                .as("exactly at the edge")
                .singleElement();
        assertThat(detector.detect(
                List.of(existing(1, FOOD, "15.00", 0)),
                List.of(fileRow(2, FOOD, "15.00", WINDOW_DAYS + 1))))
                .as("one day past the edge")
                .isEmpty();
    }

    @Test
    @DisplayName("UC-11: the window reaches backwards as well as forwards")
    void theWindowReachesBothWays() {

        assertThat(detector.detect(
                List.of(existing(1, FOOD, "15.00", 10)),
                List.of(fileRow(2, FOOD, "15.00", 10 - WINDOW_DAYS))))
                .singleElement();
    }

    @Test
    @DisplayName("UC-11: a row whose category could not be resolved is never a duplicate")
    void anUnresolvedCategoryDoesNotFlag() {

        assertThat(detector.detect(
                List.of(existing(1, FOOD, "20.00", 0)),
                List.of(fileRow(2, null, "20.00", 0))))
                .isEmpty();
    }

    @Test
    @DisplayName("UC-11: two rows that both failed to resolve are not duplicates of each other")
    void twoUnresolvedRowsDoNotMatchEachOther() {

        assertThat(detector.detect(
                List.of(),
                List.of(fileRow(2, null, "20.00", 0), fileRow(3, null, "20.00", 0))))
                .isEmpty();
    }

    @Test
    @DisplayName("UC-11: a row's amount or date missing means it cannot be compared")
    void aRowMissingAValueIsNotADuplicate() {

        assertThat(detector.detect(
                List.of(existing(1, FOOD, "20.00", 0)),
                List.of(new Candidate(2, FOOD, null, BASE_DATE))))
                .isEmpty();
        assertThat(detector.detect(
                List.of(existing(1, FOOD, "20.00", 0)),
                List.of(new Candidate(2, FOOD, new BigDecimal("20.00"), null))))
                .isEmpty();
    }

    @Test
    @DisplayName("UC-11: a file with nothing duplicated reports nothing")
    void aCleanFileReportsNothing() {

        assertThat(detector.detect(
                List.of(existing(1, FOOD, "20.00", 0)),
                List.of(fileRow(2, FOOD, "35.00", 0), fileRow(3, TRANSPORT, "20.00", 0))))
                .isEmpty();
    }

    @Test
    @DisplayName("UC-11: the same amount in a different category on the same day is not a duplicate")
    void theCategoryIsCheckedBeforeTheDate() {

        assertThat(detector.detect(
                List.of(existing(1, FOOD, "50.00", 0)),
                List.of(fileRow(2, TRANSPORT, "50.00", 0))))
                .isEmpty();
    }

    @Test
    @DisplayName("UC-11: a retuned window is obeyed, so the two features cannot disagree")
    void aRetunedWindowIsObeyed() {

        ImportDuplicateDetector oneDay = new ImportDuplicateDetector(
                new SettingReader(settingsWith("anomaly.duplicate_window_days", "1")));

        assertThat(oneDay.detect(
                List.of(existing(1, FOOD, "15.00", 0)),
                List.of(fileRow(2, FOOD, "15.00", 2))))
                .isEmpty();
    }

    @Test
    @DisplayName("UC-11: an unusable window falls back to the seeded default rather than disabling the check")
    void anUnusableWindowFallsBackToTheDefault() {

        for (String value : new String[]{"0", "-1", "many"}) {
            ImportDuplicateDetector detectorOverBadValue = new ImportDuplicateDetector(
                    new SettingReader(settingsWith("anomaly.duplicate_window_days", value)));

            assertThat(detectorOverBadValue.detect(
                    List.of(existing(1, FOOD, "15.00", 0)),
                    List.of(fileRow(2, FOOD, "15.00", WINDOW_DAYS))))
                    .as("window=%s", value)
                    .singleElement();
        }
    }

    @Test
    @DisplayName("UC-11: the same rows produce the same findings, so a re-detection can be compared")
    void theFindingsAreAFunctionOfTheInputs() {

        List<Candidate> existing = List.of(existing(1, FOOD, "25.00", 0));
        List<Candidate> rows = List.of(fileRow(2, FOOD, "25.00", 1));

        assertThat(detector.detect(existing, rows)).isEqualTo(detector.detect(existing, rows));
    }

    private static Candidate existing(long id, Long categoryId, String amount, int daysAfterBase) {
        return new Candidate((int) id, categoryId, new BigDecimal(amount), BASE_DATE.plusDays(daysAfterBase));
    }

    private static Candidate fileRow(int lineNumber, Long categoryId, String amount, int daysAfterBase) {
        return new Candidate(lineNumber, categoryId, new BigDecimal(amount), BASE_DATE.plusDays(daysAfterBase));
    }

    private static SystemSettingRepository seededSetting() {
        return settingsWith("anomaly.duplicate_window_days", String.valueOf(WINDOW_DAYS));
    }

    private static SystemSettingRepository settingsWith(String key, String value) {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        SystemSetting setting = mock(SystemSetting.class);
        when(setting.getSettingValue()).thenReturn(value);
        when(repository.findBySettingKey(key)).thenReturn(Optional.of(setting));
        return repository;
    }
}
