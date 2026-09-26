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

/**
 * UC-11's duplicate rule, tested directly.
 *
 * <p><b>Why this is a unit test while the rest of the module is not.</b> A finding is a pure function of
 * two lists and one setting, so every boundary is expressible in one line - and the boundaries are
 * exactly the cases a seeded fixture finds hardest to build. "An amount exactly equal under
 * {@code compareTo} but carrying a different scale", "a date exactly the window's width away", "two
 * rows whose categories both failed to resolve": each is trivial here and a careful sequence of dated
 * transactions through the API otherwise. The integration suite proves that a verdict reaches the table
 * and comes back on the preview; this file proves the arithmetic that decides what that verdict is.
 *
 * <p><b>The stakes are asymmetric and the tests lean that way.</b> A missed duplicate imports a record
 * twice, which the student can see and fix. A false positive silently drops a record they did have, with
 * no way to say so. So most of what follows asserts <em>not</em> flagged: a null category on either
 * side, an amount that differs by a hundredth, a date one day past the window, a row whose only twin is
 * itself. Every one of those is a conjunction that, if loosened, loses a student's data without a word.
 *
 * <p><b>The window is reached through a real {@link SettingReader}</b> over a stubbed repository, so
 * the lookup is exercised rather than bypassed and the default asserted here is the one the procedure
 * seeds. {@link #WINDOW_DAYS} is the spacing most fixtures use when they want two records to be
 * <em>unrelated</em>.
 */
class ImportDuplicateDetectorTest {

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 1);

    private static final int WINDOW_DAYS = ImportDuplicateDetector.DEFAULT_DUPLICATE_WINDOW_DAYS;

    private static final long FOOD = 4L;
    private static final long TRANSPORT = 5L;

    private final ImportDuplicateDetector detector =
            new ImportDuplicateDetector(new SettingReader(seededSetting()));

    // ------------------------------------------------------------------
    //  The rule itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: a file row matching an existing record is reported against that record's date")
    void aRowMatchingAnExistingRecordIsReported() {
        // This is the whole point: importing a statement twice imports nothing the second time rather
        // than doubling every figure. The finding names the earlier date so the preview can say which
        // record to look for.
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
        // A file listing one purchase on two consecutive lines is reported rather than imported twice,
        // so the finding's twin is the file's own earlier row even though the database holds nothing.
        List<Finding> findings = detector.detect(
                List.of(),
                List.of(fileRow(2, FOOD, "25.00", 0), fileRow(3, FOOD, "25.00", 0)));

        assertThat(findings).singleElement()
                .extracting(Finding::lineNumber).isEqualTo(3);
        assertThat(findings.getFirst().note()).contains(BASE_DATE.toString());
    }

    @Test
    @DisplayName("UC-11: a purchase listed three times yields two findings, not one")
    void chainingReportsEveryRepeatedLine() {
        // The row that was flagged is still appended to the comparison set, so the third line matches
        // the second - one purchase entered three times is two duplicates. Reporting one would leave a
        // row to import that the student clearly meant to enter once.
        List<Finding> findings = detector.detect(
                List.of(),
                List.of(fileRow(2, FOOD, "9.99", 0), fileRow(3, FOOD, "9.99", 0), fileRow(4, FOOD, "9.99", 0)));

        assertThat(findings).extracting(Finding::lineNumber).containsExactly(3, 4);
    }

    @Test
    @DisplayName("UC-11: the note names the nearest earlier twin, not the oldest one")
    void theNoteNamesTheNearestEarlierTwin() {
        // Three records of the same amount, a day apart. The last has two earlier twins and the note has
        // to name one deterministically - it names the most recent, which is the one the student is most
        // likely to have confused with it. Naming the oldest would pass a "mentions a date" assertion and
        // send them to the wrong row.
        List<Finding> findings = detector.detect(
                List.of(existing(1, FOOD, "30.00", 0), existing(2, FOOD, "30.00", 1)),
                List.of(fileRow(2, FOOD, "30.00", 2)));

        assertThat(findings.getFirst().earlierDate()).isEqualTo(BASE_DATE.plusDays(1));
        assertThat(findings.getFirst().note())
                .contains(BASE_DATE.plusDays(1).toString())
                .doesNotContain(BASE_DATE.plusDays(2).toString());
    }

    // ------------------------------------------------------------------
    //  Boundaries that must NOT flag
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: the same amount in a different category is not a duplicate")
    void aDifferentCategoryIsNotADuplicate() {
        // The category is the first conjunct. Importing a 20.00 journey as Transport must not be
        // suppressed because 20.00 was spent on Food.
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
        // DECIMAL(15,2) columns and file-parsed values need not carry the same scale even when they are
        // the same number, which is why the comparison is compareTo rather than equals. Using equals
        // would let "18.0" through as a second record of "18.00".
        assertThat(detector.detect(
                List.of(existing(1, FOOD, "18.00", 0)),
                List.of(fileRow(2, FOOD, "18.0", 0))))
                .singleElement();
    }

    @Test
    @DisplayName("UC-11: a date exactly the window's width away is a duplicate, one day more is not")
    void theWindowIsInclusiveAtItsEdge() {
        // The setting is read as "days either side", so the boundary is inclusive. A test that only
        // checked one side would pass whichever way the comparison was written.
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
        // A file's rows are compared against records the student already has, and a record entered after
        // the row's date still means the two are the same purchase. The setting says "either side", not
        // "before".
        assertThat(detector.detect(
                List.of(existing(1, FOOD, "15.00", 10)),
                List.of(fileRow(2, FOOD, "15.00", 10 - WINDOW_DAYS))))
                .singleElement();
    }

    @Test
    @DisplayName("UC-11: a row whose category could not be resolved is never a duplicate")
    void anUnresolvedCategoryDoesNotFlag() {
        // The detector errs toward not flagging: a row that will fall back to a default category at the
        // commit has no category to compare yet, and comparing an amount and a date alone would flag rows
        // on the strength of a coincidence.
        assertThat(detector.detect(
                List.of(existing(1, FOOD, "20.00", 0)),
                List.of(fileRow(2, null, "20.00", 0))))
                .isEmpty();
    }

    @Test
    @DisplayName("UC-11: two rows that both failed to resolve are not duplicates of each other")
    void twoUnresolvedRowsDoNotMatchEachOther() {
        // The other direction, and the one a naive "null == null" would get wrong: two different
        // purchases whose categories merely failed to resolve share an amount and a date and nothing
        // else.
        assertThat(detector.detect(
                List.of(),
                List.of(fileRow(2, null, "20.00", 0), fileRow(3, null, "20.00", 0))))
                .isEmpty();
    }

    @Test
    @DisplayName("UC-11: a row's amount or date missing means it cannot be compared")
    void aRowMissingAValueIsNotADuplicate() {
        // A row in state VALID always has both, so this is defensive - but the guard has to be there or
        // a null would raise rather than be ignored on a path the detector cannot rule out.
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
        // The ordinary case, and the one that must not produce a finding: a row is only ever reported
        // when an earlier record actually matches it.
        assertThat(detector.detect(
                List.of(existing(1, FOOD, "20.00", 0)),
                List.of(fileRow(2, FOOD, "35.00", 0), fileRow(3, TRANSPORT, "20.00", 0))))
                .isEmpty();
    }

    @Test
    @DisplayName("UC-11: the same amount in a different category on the same day is not a duplicate")
    void theCategoryIsCheckedBeforeTheDate() {
        // All three conjuncts have to hold. Two 50.00 records on one day under different categories are
        // two purchases, not one entered twice.
        assertThat(detector.detect(
                List.of(existing(1, FOOD, "50.00", 0)),
                List.of(fileRow(2, TRANSPORT, "50.00", 0))))
                .isEmpty();
    }

    // ------------------------------------------------------------------
    //  The setting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: a retuned window is obeyed, so the two features cannot disagree")
    void aRetunedWindowIsObeyed() {
        // The window is UC-24's key, read here rather than restated, so a student who has a record
        // flagged by one feature and imports a row matching it is told the same thing by both. A window
        // of one day makes a two-day-apart pair unrelated.
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
        // A zero or unparseable value would make the duplicate check useless if it were obeyed, so
        // SettingReader treats it as absent. The default is then the one the procedure seeds.
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

    // ------------------------------------------------------------------
    //  Findings are a function of their inputs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: the same rows produce the same findings, so a re-detection can be compared")
    void theFindingsAreAFunctionOfTheInputs() {
        // The preview recomputes the verdicts after a category override and compares them against what
        // is stored. Nothing in a finding carries a timestamp or an identifier of the run that produced
        // it, which is what makes that comparison meaningful.
        List<Candidate> existing = List.of(existing(1, FOOD, "25.00", 0));
        List<Candidate> rows = List.of(fileRow(2, FOOD, "25.00", 1));

        assertThat(detector.detect(existing, rows)).isEqualTo(detector.detect(existing, rows));
    }

    // ------------------------------------------------------------------
    //  Fixtures
    // ------------------------------------------------------------------

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
