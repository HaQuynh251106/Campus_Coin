package com.campuscoin.tips.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.tips.dto.TipListResponse;
import com.campuscoin.tips.dto.TipMonthsResponse;
import com.campuscoin.tips.dto.TipResponse;
import com.campuscoin.tips.dto.UpdateTipStateRequest;
import com.campuscoin.tips.entity.TipState;
import com.campuscoin.tips.entity.UserTip;
import com.campuscoin.tips.mapper.TipMapper;
import com.campuscoin.tips.repository.TipGenerationDao;
import com.campuscoin.tips.repository.TipViewDao;
import com.campuscoin.tips.repository.UserTipRepository;

/**
 * Showing a student their saving tips and letting them pin or dismiss one (UC-18).
 *
 * <p><b>What the database owns, and this class therefore does not restate.</b>
 *
 * <ul>
 *   <li><b>Which tips exist, and what they say</b> - {@code sp_generate_tips} applies the six rules,
 *       renders the text through {@code fn_render_template} and keeps the top N per BR-14. This class
 *       never composes advice and never decides how many tips to show.</li>
 *   <li><b>Which tips are visible, and in what order</b> - {@code v_dashboard_tips} excludes a
 *       dismissed tip and sorts pinned ones first (BR-14). This class does not filter a state or rank
 *       a tip.</li>
 *   <li><b>That a tip is not generated twice</b> - the procedure's {@code INSERT IGNORE} against
 *       {@code uk_tip_dedupe}. This class does not check whether a tip already exists before calling
 *       it; calling twice is safe by construction, which is what lets the scheduler retry.</li>
 *   <li><b>The pairing of a state with its timestamp</b> - {@code ck_tip_state}. This class names the
 *       state; {@code UserTip.setState} writes the timestamp beside it.</li>
 * </ul>
 *
 * <p><b>What is genuinely this class's.</b> Which month a request means, that a tip is reachable only
 * by its owner, which state transitions are offered, and when to ask the generator to run.
 *
 * <p><b>There is no user id parameter on any method.</b> The account comes from the verified token,
 * so there is no way to ask for somebody else's tips, and every query is bound with the caller's id.
 * That is the whole of UC-18's ownership requirement, and it is enforced by the query rather than by
 * a check afterwards (BR-02). A tip's title and body are readable prose about the student's own
 * spending, so a missing filter would leak a sentence rather than a number.
 */
@Service
public class TipService {

    private static final Logger log = LoggerFactory.getLogger(TipService.class);

    /**
     * The zone the application judges "this month" in.
     *
     * <p>The same {@code +07:00} the database session is pinned to by
     * {@code connection-init-sql: SET time_zone = '+07:00'} and that {@code ReportService} and
     * {@code TransactionService} use. A month boundary is the one place a JVM in another zone would
     * disagree with the database for part of every day, and the effective month is what a request
     * with no {@code month} resolves to.
     */
    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final TipViewDao tipViewDao;
    private final UserTipRepository userTipRepository;
    private final TipGenerationDao tipGenerationDao;
    private final TipMapper tipMapper;

    public TipService(TipViewDao tipViewDao,
                      UserTipRepository userTipRepository,
                      TipGenerationDao tipGenerationDao,
                      TipMapper tipMapper) {
        this.tipViewDao = tipViewDao;
        this.userTipRepository = userTipRepository;
        this.tipGenerationDao = tipGenerationDao;
        this.tipMapper = tipMapper;
    }

    /**
     * UC-18: the caller's tips for a month, or for the current month when none is named.
     *
     * <p>{@code readOnly = true} documents that nothing is written - reading the tips screen never
     * generates a tip and never changes one. Generating is a separate, explicit call, so a student
     * opening the screen cannot alter their own advice list by looking at it; the same distinction
     * {@code DashboardService} draws for the dashboard it renders from the same view.
     *
     * <p><b>An empty list is a real answer, not a {@code 404}.</b> A month with no tips, or whose tips
     * were all dismissed, is a month the student has no advice to show for - a fact about their own
     * data rather than a missing resource. The month is echoed back so the caller can confirm which
     * month was answered.
     *
     * @param month {@code yyyy-MM}, or null/blank for the current month
     * @throws RequestValidationException if {@code month} is present but is not a valid month
     */
    @Transactional(readOnly = true)
    public TipListResponse listTips(AuthenticatedUser principal, String month) {
        LocalDate periodMonth = resolveMonth(month);

        return tipMapper.toListResponse(periodMonth,
                tipViewDao.findTips(principal.userId(), periodMonth));
    }

    /**
     * UC-18: the months the caller has tips to show for, newest first.
     *
     * <p>Read from the same view the tips endpoint reads, so the months offered are exactly the months
     * that would return something. Backs a month picker without inventing a range: a student who has
     * never had tips generated gets an empty array, and the current month appears only if it actually
     * holds a tip.
     */
    @Transactional(readOnly = true)
    public TipMonthsResponse listMonths(AuthenticatedUser principal) {
        return tipMapper.toMonthsResponse(tipViewDao.findMonthsWithTips(principal.userId()));
    }

    /**
     * UC-18: ask the generator to produce this month's tips, and return them.
     *
     * <p><b>Why this is on demand as well as scheduled.</b> The daily job keeps a student's tips
     * current without them doing anything, but it runs on a timer - a student who records a large
     * purchase and wants the advice it should produce would otherwise wait until the next tick. This
     * endpoint runs the same generator for the current month immediately.
     *
     * <p><b>Safe to call repeatedly.</b> {@code sp_generate_tips} skips a tip that already exists for
     * its {@code user_id|period_month|tip_template_id|category_id} key, so a second call in the same
     * month produces nothing new and, crucially, cannot resurrect a tip the student dismissed or
     * reorder one they pinned. A student pressing "refresh" cannot undo their own choices.
     *
     * <p><b>The current month, not a caller-named one.</b> Generating for an arbitrary past month
     * would let a client pull advice about a month whose tips were never meant to be shown; the
     * month is not a parameter here. The effective month is the one the response names, echoed from
     * the same value the query used.
     *
     * <p>Returns the tips <em>after</em> generation, so the caller sees the advice the run produced
     * without a second request - the generate is the action and the list is its result.
     */
    @Transactional
    public TipListResponse generateTips(AuthenticatedUser principal) {
        Long userId = principal.userId();
        LocalDate periodMonth = currentMonth();

        tipGenerationDao.generateTips(userId, periodMonth);

        log.info("Saving tips generated userId={} periodMonth={}", userId, periodMonth);

        return tipMapper.toListResponse(periodMonth,
                tipViewDao.findTips(userId, periodMonth));
    }

    /**
     * UC-18: move one of the caller's tips to a new state.
     *
     * <p>Pins it, dismisses it, or clears either - which is what {@code NEW} means and what "unpin"
     * does. The endpoint is one route for the one field a student may change, rather than a route per
     * transition, because the transitions differ only in the value they set.
     *
     * <p><b>Asking for the state the tip already holds is not an error.</b> A second pin, or a
     * dismissal of an already-dismissed tip, is answered with the tip and the timestamp it first
     * received, because the end state the caller wants is the one that holds. That is the treatment
     * {@code sp_mark_notification_read} gives a second mark-read, and it is what makes the action safe
     * to retry.
     *
     * <p><b>Dismissal is one-way.</b> A tip that is {@code DISMISSED} cannot be moved back to
     * {@code NEW} or {@code PINNED}: a tip the student threw away is not brought back by asking
     * again, and the alternative - a route that quietly un-dismisses - would let a stale client
     * restore advice the student deleted. This is the one transition the request is refused for
     * rather than applied, and it is refused as a conflict: the value sent is valid, it just does not
     * apply to the tip's current state. The remedy is to dismiss is not offered in reverse; the
     * caller asks once and it stays dismissed.
     *
     * <p>Ownership is decided by the query that loads the tip - {@code findByIdAndUserId} takes the
     * caller's id - so a tip belonging to another student is not found rather than found and refused.
     * The subsequent write is a plain Hibernate update with no ownership predicate of its own, which
     * is precisely why the load must be the one that already named the caller.
     *
     * <p><b>The row is locked by that load, and the transition is decided from the locked state.</b>
     * Both of this method's decisions - whether dismissal is being reversed, and whether anything is
     * changing at all - are answers to "what state is it in now", so two of them taken from a
     * lock-free read could both be taken. A pin and a dismiss racing could each see {@code NEW} and
     * each succeed, with the later write silently replacing the earlier one: the caller who pinned
     * would be told {@code PINNED} while the row ended up dismissed. The lock makes the second request
     * wait and then decide against what the first actually wrote - so the outcome is one of the two
     * requests' genuine states rather than a blend of both.
     *
     * @throws NotFoundException          if the tip does not exist or is not the caller's
     * @throws RequestValidationException if the tip is dismissed and the request would revive it
     */
    @Transactional
    public TipResponse changeState(AuthenticatedUser principal, Long tipId,
                                   UpdateTipStateRequest request) {
        Long userId = principal.userId();
        TipState requested = request.state();

        UserTip tip = userTipRepository.findByIdAndUserId(tipId, userId)
                .orElseThrow(() -> new NotFoundException("Tip not found."));

        TipState current = tip.getState();

        if (current == TipState.DISMISSED && requested != TipState.DISMISSED) {
            throw new RequestValidationException(
                    "A dismissed tip cannot be brought back.",
                    List.of(new ApiError.FieldError("state",
                            "This tip was dismissed and cannot be restored.")));
        }

        if (current == requested) {
            // Nothing to change. Answered with the tip rather than refused, so a retry or two devices
            // acting at once settle on the state that already holds - and the stored timestamp is the
            // one from when the state was first taken, not from this attempt.
            return tipMapper.toTipResponse(tip);
        }

        tip.setState(requested, LocalDateTime.now(APPLICATION_ZONE));
        userTipRepository.saveAndFlush(tip);

        log.info("Tip state changed userId={} tipId={} from={} to={}",
                userId, tipId, current, requested);

        return tipMapper.toTipResponse(tip);
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /** The current month in the application's zone, as the first-of-month {@code DATE} the views use. */
    private LocalDate currentMonth() {
        return YearMonth.from(LocalDate.now(APPLICATION_ZONE)).atDay(1);
    }

    /**
     * The month a tips request means: the named one, or the current month when none was given.
     *
     * <p>Parsing is strict - {@code YearMonth.parse} accepts {@code 2026-09} and refuses
     * {@code 2026-9} and {@code 2026-13} - so a malformed month cannot be silently read as a
     * different, valid one. The refusal names the parameter so a client can correct the request.
     */
    private LocalDate resolveMonth(String month) {
        if (month == null || month.isBlank()) {
            return currentMonth();
        }
        try {
            return YearMonth.parse(month.trim()).atDay(1);
        } catch (RuntimeException ex) {
            throw new RequestValidationException(
                    "The month is not a valid month.",
                    List.of(new ApiError.FieldError("month",
                            "Enter a real month in yyyy-MM form, for example 2026-09.")));
        }
    }
}
