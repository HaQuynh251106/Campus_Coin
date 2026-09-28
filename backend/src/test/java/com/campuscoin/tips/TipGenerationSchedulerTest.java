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

class TipGenerationSchedulerTest {

    @Test
    @DisplayName("UC-18: the run generates for every active student once, leaving the month to the database")
    void theRunCoversEveryActiveStudentOnce() {
        TipGenerationDao dao = mock(TipGenerationDao.class);
        when(dao.findActiveStudentIds()).thenReturn(List.of(2L, 7L, 9L));

        new TipGenerationScheduler(dao).generateMonthlyTips();

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

        verify(dao, times(1)).generateTips(eq(7L), isNull());
    }

    @Test
    @DisplayName("UC-18: a run that cannot read its student list is logged, not thrown at the framework")
    void aFailureToReadTheStudentListDoesNotPropagate() {
        TipGenerationDao dao = mock(TipGenerationDao.class);
        when(dao.findActiveStudentIds()).thenThrow(new IllegalStateException("database unavailable"));

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

        verify(dao, never()).generateTips(any(), any());
    }
}
