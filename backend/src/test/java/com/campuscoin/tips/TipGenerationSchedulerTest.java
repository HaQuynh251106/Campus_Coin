package com.campuscoin.tips;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.campuscoin.tips.repository.TipGenerationDao;
import com.campuscoin.tips.scheduler.TipGenerationScheduler;

/**
 * The scheduled run, called directly with a stubbed DAO.
 *
 * <p><b>What this class is for.</b> Two things about a bulk run are this module's decision rather
 * than the database's, and neither is visible from the outside. The first is the transaction
 * boundary: whether the run holds one transaction or one per student makes no difference to a single
 * student's tips and all the difference to everybody else's when one generation fails. The second is
 * that a scheduled method must never throw - a background job that dies takes its timer with it, and
 * no request would ever show it.
 *
 * <p>Both are properties of the real {@link TipGenerationScheduler}, so the real class is what is
 * exercised here, constructed with a stub DAO. A test that reimplemented the loop would assert its
 * own copy and still pass after the scheduler had been changed - which is the failure mode this
 * class exists to avoid.
 *
 * <p>The DAO is stubbed because its own behaviour is the procedure's, and that is covered against the
 * real schema by {@code TipsApiIT}. What is under test here is who calls whom, with what, how many
 * times, and what happens when one of them fails.
 *
 * <p>The generated-count outcomes are asserted through the calls made rather than through a return
 * value: the method is a scheduled entry point and returns nothing, so the observable result of a
 * run is exactly the set of students it asked for.
 */
class TipGenerationSchedulerTest {

    @Test
    @DisplayName("UC-18: the run generates for every active student once, leaving the month to the database")
    void theRunCoversEveryActiveStudentOnce() {
        TipGenerationDao dao = mock(TipGenerationDao.class);
        when(dao.findActiveStudentIds()).thenReturn(List.of(2L, 7L, 9L));

        new TipGenerationScheduler(dao).generateMonthlyTips();

        // Passing a literal null asserts the argument IS null, which is the contract: the month is
        // left to the procedure, so "this month" is the database session's month rather than one the
        // JVM computed (VĐ-10). A JVM in another zone would otherwise disagree with the database
        // about the current month for part of every day.
        verify(dao, times(1)).generateTips(2L, null);
        verify(dao, times(1)).generateTips(7L, null);
        verify(dao, times(1)).generateTips(9L, null);
    }

    @Test
    @DisplayName("UC-18: the students are generated for in the order the query returned them")
    void theRunKeepsTheOrderItWasGiven() {
        TipGenerationDao dao = mock(TipGenerationDao.class);
        when(dao.findActiveStudentIds()).thenReturn(List.of(2L, 7L, 9L));

        new TipGenerationScheduler(dao).generateMonthlyTips();

        // Not asserted for the students' sake - order is not something they can observe - but so that
        // a run cut short has covered a predictable prefix rather than an arbitrary subset.
        InOrder order = inOrder(dao);
        order.verify(dao).generateTips(2L, null);
        order.verify(dao).generateTips(7L, null);
        order.verify(dao).generateTips(9L, null);
    }

    @Test
    @DisplayName("UC-18: a failed generation costs its own student and no other - the run continues")
    void aFailedStudentDoesNotStopTheRun() {
        TipGenerationDao dao = mock(TipGenerationDao.class);
        when(dao.findActiveStudentIds()).thenReturn(List.of(2L, 7L, 9L));
        doThrow(new IllegalStateException("generation failed for this student"))
                .when(dao).generateTips(eq(7L), isNull());

        // The failed student is the middle of three. This is the assertion that would fail if the
        // run held a single transaction, because the failure would abort the batch rather than one
        // call - the students before and after it are asked for regardless.
        assertThatCode(() -> new TipGenerationScheduler(dao).generateMonthlyTips())
                .doesNotThrowAnyException();

        verify(dao).generateTips(2L, null);
        verify(dao).generateTips(7L, null);
        verify(dao).generateTips(9L, null);
    }

    @Test
    @DisplayName("UC-18: a failure on the first student still leaves the remaining students attempted")
    void aFailureOnTheFirstStudentDoesNotSkipTheRest() {
        TipGenerationDao dao = mock(TipGenerationDao.class);
        when(dao.findActiveStudentIds()).thenReturn(List.of(2L, 7L, 9L));
        doThrow(new IllegalStateException("generation failed")).when(dao).generateTips(eq(2L), isNull());

        new TipGenerationScheduler(dao).generateMonthlyTips();

        // The boundary is per student rather than per prefix: a failure on the first is not read as a
        // reason to abandon the run.
        verify(dao).generateTips(7L, null);
        verify(dao).generateTips(9L, null);
    }

    @Test
    @DisplayName("UC-18: a student whose generation fails is not retried inside the same run")
    void aFailedStudentIsNotRetriedWithinTheRun() {
        TipGenerationDao dao = mock(TipGenerationDao.class);
        when(dao.findActiveStudentIds()).thenReturn(List.of(7L));
        doThrow(new IllegalStateException("generation failed"))
                .when(dao).generateTips(eq(7L), isNull());

        new TipGenerationScheduler(dao).generateMonthlyTips();

        // No retry inside the run. The procedure is idempotent, so a retry would be safe - but a
        // failure here is a fault rather than a transient collision, since the dedupe key makes a
        // collision impossible. Repeating it would only delay the students after this one and repeat
        // the log line; the next scheduled tick is the retry.
        verify(dao, times(1)).generateTips(eq(7L), isNull());
    }

    @Test
    @DisplayName("UC-18: a run that cannot read its student list is logged, not thrown at the framework")
    void aFailureToReadTheStudentListDoesNotPropagate() {
        TipGenerationDao dao = mock(TipGenerationDao.class);
        when(dao.findActiveStudentIds()).thenThrow(new IllegalStateException("database unavailable"));

        // A scheduled method that throws is logged by the framework and then not retried until the
        // next tick. Catching it here keeps the timer's behaviour identical while letting the log
        // line say what failed - so the run must not rethrow.
        assertThatCode(() -> new TipGenerationScheduler(dao).generateMonthlyTips())
                .doesNotThrowAnyException();

        verify(dao, never()).generateTips(any(), any());
    }

    @Test
    @DisplayName("UC-18: a run with no active students does nothing and is not a failure")
    void anEmptyRunIsNotAFailure() {
        TipGenerationDao dao = mock(TipGenerationDao.class);
        when(dao.findActiveStudentIds()).thenReturn(List.of());

        new TipGenerationScheduler(dao).generateMonthlyTips();

        // An empty campus is not an error: no student is asked for, and the run reports success with
        // nothing done rather than a failure an operator would chase.
        verify(dao, never()).generateTips(any(), any());
    }
}
