package com.campuscoin.recurring.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;

import com.campuscoin.category.entity.Category;
import com.campuscoin.category.entity.CategoryType;

/**
 * A row of {@code recurring_rules}: a template that the scheduler turns into transactions (UC-09).
 *
 * <p><b>{@code type} is stored here, unlike on {@code transactions}, and it is still not a field a
 * client may send.</b> The schema keeps the column because a rule can be created before any
 * transaction exists, and BR-05 has to be checked at that moment rather than when the scheduler
 * eventually posts. {@code trg_recurring_rules_before_insert} and {@code ..._before_update} compare
 * it against {@code categories.type} and refuse a mismatch. Java writes it from the category on
 * every path - the factory and {@link #setCategory} both do - so the two cannot drift, which is the
 * same one-source-of-truth arrangement the transaction module gets by having no column at all.
 *
 * <p><b>{@code start_date} has no setter, deliberately.</b> It is the rule's origin: the periods the
 * scheduler has already posted for are a function of it, so moving it would silently rewrite what
 * the rule means and disagree with the occurrences already recorded. A student who wants the rule to
 * run on a different day changes {@code next_run_date} - the field that actually drives the
 * scheduler - through the update endpoint.
 *
 * <p><b>{@code last_run_date} is written only by {@code sp_post_recurring_transactions}</b>, so it is
 * mapped {@code updatable = false} and has no setter. The procedure changes it with SQL Hibernate
 * never sees, which is why no JPA statement may compete with it.
 *
 * <p><b>{@code @DynamicUpdate} is a correctness requirement here, for the same reason it is on
 * {@code Transaction}.</b> The scheduler advances {@code next_run_date} and {@code last_run_date} in
 * the database while a request may hold the row in memory. A plain Hibernate {@code UPDATE} writes
 * every mapped column back with the values read when the request began, so an edit that changed only
 * the amount could write the scheduler's cursor back to where it was - re-posting periods that were
 * already posted, or skipping ones that were due. Restricting the statement to the columns that
 * actually changed removes that race. {@code @DynamicInsert} is not applied: the INSERT names every
 * mapped column on purpose, which is why the factory sets the ones the schema gives defaults for.
 *
 * <p><b>Columns deliberately left unmapped.</b> {@code day_of_month} and {@code day_of_week} are
 * described by the schema as hints for the UI, and {@code sp_post_recurring_transactions} does not
 * read either of them - it schedules from {@code next_run_date} alone. Writing them here would store
 * a value with no consumer and, worse, one that could disagree with the date the rule actually runs
 * on. Leaving them out means this module cannot create that contradiction; a later screen that wants
 * a human-readable "every month on the 5th" can derive it from {@code start_date}, which is the value
 * the scheduler agrees with. {@code created_at} and {@code updated_at} are unmapped too, matching the
 * other modules, none of which publishes creation metadata.
 */
@Entity
@Table(name = "recurring_rules")
@DynamicUpdate
public class RecurringRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The owning student.
     *
     * <p>A plain identifier rather than an association, as on {@code Transaction}: nothing here needs
     * the owner's row, and a {@code @ManyToOne} would load a full {@code User} - including its
     * password hash and token version - to answer a question about ownership. Ownership is enforced
     * in the query instead: every lookup in {@code RecurringRuleRepository} takes the caller's id
     * alongside the rule's.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * The category the rule posts under.
     *
     * <p>An association because the category supplies the type (BR-05) and the name, icon and colour
     * every list row renders. {@code FetchType.LAZY} is set explicitly - {@code @ManyToOne} defaults
     * to EAGER - and the queries that need it say so with {@code JOIN FETCH}, because
     * {@code spring.jpa.open-in-view} is false and a mapping that forgot would fail loudly rather
     * than quietly issuing a query per row.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /**
     * BR-05: whether this rule is income or expense. Always the category's own value.
     *
     * <p>Written by the factory and by {@link #setCategory}, never from a request body. The two
     * triggers on this table refuse a value that disagrees with {@code categories.type}, so the write
     * below is a restatement of a rule that already holds - kept because the INSERT must name a NOT
     * NULL column, and taking it from the category is the only value that can pass.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, columnDefinition = "enum('INCOME','EXPENSE')")
    private CategoryType type;

    /** {@code ck_recurring_amount} requires a positive value; the DTO refuses 0 before the write. */
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false,
            columnDefinition = "enum('DAILY','WEEKLY','MONTHLY','QUARTERLY','YEARLY')")
    private RecurringFrequency frequency;

    /** "Every N periods" - 2 with {@code WEEKLY} means fortnightly. {@code ck_recurring_interval}. */
    @Column(name = "interval_count", nullable = false, columnDefinition = "smallint unsigned")
    private Integer intervalCount;

    /** The rule's origin. No setter: see the note on the class. */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** {@code ck_recurring_dates} requires it to be on or after {@code start_date}, when present. */
    @Column(name = "end_date")
    private LocalDate endDate;

    /**
     * The date the scheduler will post next - the cursor it actually reads.
     *
     * <p>Set to {@code start_date} when the rule is created, so the first occurrence is the start
     * date, and advanced by the procedure after each run. Writable through the update endpoint
     * because it is the only way to move a rule onto a different day: {@code start_date} is the
     * origin and {@code day_of_month} is a hint the scheduler ignores, so this is the field that
     * decides when the rule next fires.
     */
    @Column(name = "next_run_date", nullable = false)
    private LocalDate nextRunDate;

    /** Set by the scheduler to the latest period it posted. Written by SQL, so not updatable here. */
    @Column(name = "last_run_date", updatable = false)
    private LocalDate lastRunDate;

    /** Only {@code ACTIVE} is selected by the scheduler - see {@link RecurringStatus}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false,
            columnDefinition = "enum('ACTIVE','PAUSED','ENDED')")
    private RecurringStatus status;

    protected RecurringRule() {
        // Required by JPA.
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Category getCategory() {
        return category;
    }

    public CategoryType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getDescription() {
        return description;
    }

    public RecurringFrequency getFrequency() {
        return frequency;
    }

    public Integer getIntervalCount() {
        return intervalCount;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalDate getNextRunDate() {
        return nextRunDate;
    }

    public LocalDate getLastRunDate() {
        return lastRunDate;
    }

    public RecurringStatus getStatus() {
        return status;
    }

    // --- Writable through the update endpoint ----------------------------------------------------
    // Setters exist only for the fields a student may change. There is none for user_id (a rule
    // cannot be handed to another student), start_date (the rule's origin), last_run_date (the
    // scheduler's) or type (the category's).

    /**
     * Moves the rule to another category, which also decides whether it is income or expense (BR-05).
     *
     * <p>The type follows the category here rather than being set separately, because the two must
     * agree: {@code trg_recurring_rules_before_update} re-runs the BR-05 comparison on every update,
     * so a caller that changed the category without changing the type would be refused by the
     * database. Keeping the two in one method means that mistake cannot be expressed.
     */
    public void setCategory(Category category) {
        this.category = category;
        this.type = category.getType();
    }

    /** UC-09: change how much the rule posts. */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /** UC-09. */
    public void setDescription(String description) {
        this.description = description;
    }

    /** UC-09: change how often the rule fires. */
    public void setFrequency(RecurringFrequency frequency) {
        this.frequency = frequency;
    }

    /** UC-09: "every N periods". */
    public void setIntervalCount(Integer intervalCount) {
        this.intervalCount = intervalCount;
    }

    /** UC-09: set or clear the date the rule stops. A null clears it, making the rule open-ended. */
    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    /** UC-09: move the rule onto a different day. See the note on the class for why this and not
     * {@code startDate}. */
    public void setNextRunDate(LocalDate nextRunDate) {
        this.nextRunDate = nextRunDate;
    }

    /**
     * UC-09: pause, resume or end the rule.
     *
     * <p>{@code ENDED} is what this module offers instead of deleting a rule that has already posted
     * transactions - see {@code RecurringRuleService#delete}.
     */
    public void setStatus(RecurringStatus status) {
        this.status = status;
    }

    /**
     * Builds a rule owned by one student, ready for the scheduler.
     *
     * <p>{@code user_id}, {@code type} and {@code status} are fixed here rather than left to a caller:
     * the owner comes from the token, the type from the category (BR-05), and a rule that is created
     * already stopped would be a rule nobody asked for. The schema's own default for {@code status}
     * is {@code ACTIVE}, and writing it explicitly keeps the INSERT complete - an omitted column
     * would fall back to that default anyway, but naming it means the value the row starts with is
     * visible in this class rather than only in the schema.
     *
     * <p>{@code next_run_date} is the caller-supplied first run, which the service sets to the start
     * date. Nothing here decides whether the rule is acceptable: the category's ownership and active
     * state (BR-02, BR-07) and the BR-05 type agreement are {@code sp_validate_recurring_rule}'s,
     * reached through the two triggers; the service asks the same questions first only so the caller
     * receives an error naming the field.
     *
     * @param userId        the owning student, never null
     * @param category      a category the student may use, validated by the service
     * @param amount        strictly positive
     * @param description   trimmed by the service, or null
     * @param frequency     how often the rule fires
     * @param intervalCount how many periods between runs, at least 1
     * @param startDate     the rule's origin
     * @param endDate       when the rule stops, or null for open-ended
     * @param nextRunDate   the first date the scheduler should post
     */
    public static RecurringRule newRule(Long userId,
                                        Category category,
                                        BigDecimal amount,
                                        String description,
                                        RecurringFrequency frequency,
                                        Integer intervalCount,
                                        LocalDate startDate,
                                        LocalDate endDate,
                                        LocalDate nextRunDate) {
        RecurringRule rule = new RecurringRule();
        rule.userId = userId;
        rule.category = category;
        rule.type = category.getType();
        rule.amount = amount;
        rule.description = description;
        rule.frequency = frequency;
        rule.intervalCount = intervalCount;
        rule.startDate = startDate;
        rule.endDate = endDate;
        rule.nextRunDate = nextRunDate;
        rule.status = RecurringStatus.ACTIVE;
        return rule;
    }
}
