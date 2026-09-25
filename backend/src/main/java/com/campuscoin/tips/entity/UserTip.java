package com.campuscoin.tips.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;

/**
 * A row of {@code user_tips}: one saving tip generated for one student (UC-18).
 *
 * <p><b>The row is written once, by the database, and then only its state changes.</b> Every tip is
 * produced by {@code sp_generate_tips}, which renders the title and body from a {@code tip_templates}
 * row and decides the {@code potential_saving} and {@code rank_score} that BR-14 ranks by. This
 * entity is therefore never inserted through Hibernate - there is no factory method - and the fields
 * the procedure computed have no setters. The one thing UC-18 lets a student change is the tip's
 * {@link #state}, and {@link #setState} is the only write this class exposes.
 *
 * <p><b>Why the state transition is an entity write and not a procedure call.</b> The budget and
 * notification modules each move their one writable field with a stored procedure
 * ({@code sp_mark_notification_read}, {@code sp_soft_delete_transaction}) because the schema provides
 * one, and the procedure folds the ownership check into the same statement as the write. The schema
 * provides <em>no</em> procedure for a tip's state - pinning and dismissing are not among the 24 - so
 * the closest available arrangement is the one the recurring-rule module uses for its
 * {@code status}: a single-field update whose ownership is checked first and whose validator is what
 * the caller would otherwise have to trust. The ownership check is not optional here and is not left
 * to the update statement; see {@code TipService#changeState}.
 *
 * <p><b>{@code user_id} is mapped but never published.</b> It is what every ownership query filters
 * on; the response omits it, because a client has no use for it and must never be tempted to send one
 * back.
 *
 * <p><b>{@code dedupe_key} is deliberately not mapped.</b> It is a {@code VIRTUAL} generated column
 * that exists only so {@code uk_tip_dedupe} can stop {@code sp_generate_tips} from producing the same
 * tip twice in one period - the same reason {@code scope_key} is unmapped on {@code Category} and
 * {@code key_hash} on {@code PasswordResetToken}. Nothing reads it, and mapping it would invite
 * Hibernate to try to write a value the database computes itself.
 *
 * <p>{@code tip_template_id} is also left unmapped. It records which template a tip came from, which
 * is how the generator dedupes and how BR-14 is traced back to {@code tip_templates} - library
 * bookkeeping rather than something a student's tip screen shows. The response carries the rendered
 * {@code title} and {@code body}, which are what a client displays.
 *
 * <p>{@link DynamicUpdate} is applied so a state change writes only {@code state} and the timestamp
 * beside it, rather than writing every mapped column back with the values read when the request
 * began. A concurrent generation of the next month's tips must not be undone by a pin request that
 * happens to carry a stale copy of the row.
 */
@Entity
@Table(name = "user_tips")
@DynamicUpdate
public class UserTip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** The owning student. Never published; used by every ownership query. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The first day of the month this tip is about. Set by the generator. */
    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    /**
     * The category the tip is about, or {@code null}.
     *
     * <p>Nullable because some tips belong to no single category - the savings-goal tip and the
     * "not enough data yet" tip are about the month as a whole. The column is nullable and so is the
     * response field, so a client can link a tip to a category's transactions and omit the link when
     * there is nothing to link to.
     *
     * <p>Mapped as a plain identifier rather than an association: nothing in this module needs the
     * category's row, and the tip already carries its rendered title, which names the category.
     */
    @Column(name = "category_id")
    private Long categoryId;

    /** The headline, rendered from the template by {@code fn_render_template}. Read-only here. */
    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /** The advice itself, rendered by {@code fn_render_template}. Read-only here. */
    @Column(name = "body", nullable = false)
    private String body;

    /** What following the advice is estimated to save (BR-14). Read-only here. */
    @Column(name = "potential_saving", nullable = false)
    private BigDecimal potentialSaving;

    /**
     * The value BR-14 ranks tips by. Read-only here.
     *
     * <p>Not mapped for publication - the ordering it produces is the contract, and the API returns
     * the list already ranked, so exposing the raw score would be a second expression of an order
     * the response already carries. It is selected only where the DAO needs it.
     */
    @Column(name = "rank_score", nullable = false)
    private BigDecimal rankScore;

    /**
     * Whether the tip is new, pinned or dismissed.
     *
     * <p>{@code columnDefinition} reproduces the schema's ENUM. Hibernate validates a column by the
     * type name MySQL reports, and a {@code @Enumerated(STRING)} field would otherwise be expected to
     * be a {@code varchar}, which {@code ddl-auto=validate} would refuse.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false,
            columnDefinition = "enum('NEW','PINNED','DISMISSED')")
    private TipState state;

    /** When the student pinned it, or null. {@code ck_tip_state} ties this to {@link #state}. */
    @Column(name = "pinned_at")
    private LocalDateTime pinnedAt;

    /** When the student dismissed it, or null. {@code ck_tip_state} ties this to {@link #state}. */
    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    /** When the generator produced it. Set by the database default. */
    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    protected UserTip() {
        // Required by JPA.
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDate getPeriodMonth() {
        return periodMonth;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public BigDecimal getPotentialSaving() {
        return potentialSaving;
    }

    public BigDecimal getRankScore() {
        return rankScore;
    }

    public TipState getState() {
        return state;
    }

    public LocalDateTime getPinnedAt() {
        return pinnedAt;
    }

    public LocalDateTime getDismissedAt() {
        return dismissedAt;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    // --- Writable by UC-18 -----------------------------------------------------------------------

    /**
     * Moves the tip to a new state, setting or clearing the timestamp beside it.
     *
     * <p><b>The state and its timestamp are written together, and both are set here rather than by
     * the caller.</b> {@code ck_tip_state} is a two-field constraint: {@code PINNED} must carry a
     * {@code pinned_at}, {@code DISMISSED} a {@code dismissed_at}, and {@code NEW} neither. A setter
     * that took only a {@code TipState} would let a caller move {@code state} while leaving the old
     * timestamp in place - a pinned tip carrying a stale {@code dismissed_at} - and the database
     * would refuse the write. Deriving both fields from the one argument makes that unrepresentable.
     *
     * <p><b>{@code NEW} restores a tip that was pinned, by clearing the timestamp.</b> Pinning is a
     * display preference the student can take back; dismissing is not, and this method will not move
     * a tip out of {@code DISMISSED}. That is enforced by the service, which does not offer the
     * transition, rather than here - this class's job is to keep the state and its timestamp
     * consistent, not to decide which transitions are allowed.
     *
     * @param newState the state to move to, never null
     * @param now      the timestamp to record for a pin or a dismissal, in the application's zone
     */
    public void setState(TipState newState, LocalDateTime now) {
        this.state = newState;
        switch (newState) {
            case PINNED -> {
                this.pinnedAt = now;
                this.dismissedAt = null;
            }
            case DISMISSED -> {
                this.dismissedAt = now;
                this.pinnedAt = null;
            }
            case NEW -> {
                this.pinnedAt = null;
                this.dismissedAt = null;
            }
        }
    }
}
