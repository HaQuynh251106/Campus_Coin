package com.campuscoin.auth.entity;

import java.math.BigDecimal;
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
 * A row of {@code users}.
 *
 * <p>{@code password_hash} only ever holds a bcrypt hash (BR-01). There is no trigger or CHECK
 * enforcing the format - the schema records the intent in a column comment - so producing the
 * hash is this module's responsibility, and the plain password is never stored or logged.
 *
 * <p>The profile and preference columns (academic year, allowance baseline, savings goal, theme,
 * font scale) were added for UC-04 and UC-27, which own them. They are fields of this entity
 * rather than of a second one because the schema has no separate profile table: the columns live
 * on {@code users} and a second entity mapped to the same table would mean two persistence
 * contexts able to write the same row.
 *
 * <p>Mapping a column here is not the same as letting a client write it. The request DTOs decide
 * what is writable, and {@code role}, {@code status}, {@code token_version} and
 * {@code password_hash} have no setter path from any request body - they belong to UC-01, UC-02,
 * UC-03 and, for status, UC-22.
 *
 * <p>{@link DynamicUpdate} is not an optimisation here, it is a correctness requirement. A plain
 * Hibernate {@code UPDATE} writes every mapped column, so a profile edit would send back the
 * {@code status} and {@code token_version} values it read when the request began. If another
 * request changed either one in the meantime - a password reset bumping {@code token_version},
 * an administrator disabling the account (UC-22) - that write would silently undo it, and the
 * revoked tokens the change was meant to kill would keep working. Restricting the statement to
 * the columns that actually changed removes that class of lost update.
 *
 * <p>Only {@code @DynamicUpdate} is applied, not {@code @DynamicInsert}: an INSERT still names
 * every column on purpose, which is why {@link #newStudent} sets the columns the schema gives
 * defaults for.
 */
@Entity
@Table(name = "users")
@DynamicUpdate
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "email", length = 190, nullable = false)
    private String email;

    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Column(name = "full_name", length = 120, nullable = false)
    private String fullName;

    /**
     * UC-04: the student's year of study, a free label such as {@code Year 3}. Nullable in the
     * schema, so the profile may legitimately have no value and the API reports it as absent
     * rather than inventing a default.
     */
    @Column(name = "academic_year", length = 30)
    private String academicYear;

    /**
     * VĐ-04: the amount the student expects to receive each month. Feeds the dashboard's
     * savings-goal view. {@code ck_users_money} rejects a negative value, and the API validates
     * the same rule first so the caller gets a field error rather than a generic conflict.
     */
    @Column(name = "monthly_allowance_baseline", nullable = false)
    private BigDecimal monthlyAllowanceBaseline;

    /** VĐ-04: the amount the student aims to save each month, read by {@code v_dashboard_summary}. */
    @Column(name = "monthly_savings_goal", nullable = false)
    private BigDecimal monthlySavingsGoal;

    /** UC-27: the appearance preference. {@code SYSTEM} defers to the operating system. */
    @Enumerated(EnumType.STRING)
    @Column(name = "theme_pref", nullable = false, columnDefinition = "enum('LIGHT','DARK','SYSTEM')")
    private ThemePreference themePreference;

    /** UC-27: the text size the student selected. */
    @Enumerated(EnumType.STRING)
    @Column(name = "font_scale", nullable = false,
            columnDefinition = "enum('SMALL','MEDIUM','LARGE','XLARGE')")
    private FontScale fontScale;

    /**
     * {@code columnDefinition} reproduces the schema's ENUM. Hibernate validates a column by the
     * type name it reports, and would otherwise expect {@code varchar} for an enum mapped with
     * {@link EnumType#STRING}. Pinning the definition keeps {@code ddl-auto=validate} strict
     * instead of relaxing it, so a genuine schema drift is still caught at start-up.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, columnDefinition = "enum('STUDENT','ADMIN')")
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "enum('ACTIVE','DISABLED')")
    private AccountStatus status;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /**
     * BR-03: bumped by the database whenever every session must be invalidated - on password
     * reset and on account disable. A JWT carries the value it was issued with, and the token
     * filter rejects any token whose claim no longer matches, so old tokens die immediately.
     */
    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected User() {
        // Required by JPA.
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public String getAcademicYear() {
        return academicYear;
    }

    public BigDecimal getMonthlyAllowanceBaseline() {
        return monthlyAllowanceBaseline;
    }

    public BigDecimal getMonthlySavingsGoal() {
        return monthlySavingsGoal;
    }

    public ThemePreference getThemePreference() {
        return themePreference;
    }

    public FontScale getFontScale() {
        return fontScale;
    }

    // --- UC-04 profile fields -----------------------------------------------------------------
    // Setters exist only for the columns UC-04 and UC-27 may change, and each is called from
    // exactly one place: ProfileService. applyProfile / applyPreferences. There is deliberately
    // no setter for email, role, status, token_version or password_hash, so no profile request
    // can reach them even if a DTO were extended by mistake.

    /** UC-04. A blank value clears the field, which the schema allows (the column is nullable). */
    public void setAcademicYear(String academicYear) {
        this.academicYear = academicYear;
    }

    /** UC-04. Never negative: the service validates first and {@code ck_users_money} backs it up. */
    public void setMonthlyAllowanceBaseline(BigDecimal monthlyAllowanceBaseline) {
        this.monthlyAllowanceBaseline = monthlyAllowanceBaseline;
    }

    /** UC-04. Never negative, for the same reason. */
    public void setMonthlySavingsGoal(BigDecimal monthlySavingsGoal) {
        this.monthlySavingsGoal = monthlySavingsGoal;
    }

    /** UC-04. Also the name shown across the application, so it is not blank. */
    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    /** UC-27. */
    public void setThemePreference(ThemePreference themePreference) {
        this.themePreference = themePreference;
    }

    /** UC-27. */
    public void setFontScale(FontScale fontScale) {
        this.fontScale = fontScale;
    }

    public UserRole getRole() {
        return role;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Integer getTokenVersion() {
        return tokenVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Builds the only entity this module ever creates: a new self-registered student.
     *
     * <p>UC-01 B4/B5 and its postcondition fix every other value: the password arrives already
     * hashed, the role is STUDENT, the account is ACTIVE, and currency falls back to the system
     * default so the dashboard can render immediately. A new student needs no default categories
     * of their own - UC-01 B5 means they use the shared default set, which is the
     * {@code categories} rows with {@code user_id IS NULL}, already present in the database.
     *
     * <p>The profile, preference and money columns the schema defaults are set explicitly rather
     * than left unset. Hibernate includes every mapped column in the INSERT it builds, so leaving
     * one null would send an explicit NULL that overrides the column default and is then rejected
     * by the NOT NULL constraint. Writing the documented defaults here keeps the application's
     * INSERT consistent with the schema's own {@code DEFAULT} clauses.
     *
     * @param email        already normalised to lower case by the service
     * @param passwordHash a bcrypt hash, never a plain password
     */
    public static User newStudent(String email, String passwordHash, String fullName, String currency) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.fullName = fullName;
        user.role = UserRole.STUDENT;
        user.status = AccountStatus.ACTIVE;
        user.currency = currency;
        user.tokenVersion = 0;

        // UC-04: a new student has not stated a year, and both money figures start at zero,
        // which is the schema default and passes ck_users_money.
        user.academicYear = null;
        user.monthlyAllowanceBaseline = BigDecimal.ZERO;
        user.monthlySavingsGoal = BigDecimal.ZERO;

        // UC-27: follow the operating system until the student chooses otherwise.
        user.themePreference = ThemePreference.SYSTEM;
        user.fontScale = FontScale.MEDIUM;

        return user;
    }
}
