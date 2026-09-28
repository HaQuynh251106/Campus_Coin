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

    @Column(name = "academic_year", length = 30)
    private String academicYear;

    @Column(name = "monthly_allowance_baseline", nullable = false)
    private BigDecimal monthlyAllowanceBaseline;

    @Column(name = "monthly_savings_goal", nullable = false)
    private BigDecimal monthlySavingsGoal;

    @Enumerated(EnumType.STRING)
    @Column(name = "theme_pref", nullable = false, columnDefinition = "enum('LIGHT','DARK','SYSTEM')")
    private ThemePreference themePreference;

    @Enumerated(EnumType.STRING)
    @Column(name = "font_scale", nullable = false,
            columnDefinition = "enum('SMALL','MEDIUM','LARGE','XLARGE')")
    private FontScale fontScale;

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

    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected User() {

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

    public void setAcademicYear(String academicYear) {
        this.academicYear = academicYear;
    }

    public void setMonthlyAllowanceBaseline(BigDecimal monthlyAllowanceBaseline) {
        this.monthlyAllowanceBaseline = monthlyAllowanceBaseline;
    }

    public void setMonthlySavingsGoal(BigDecimal monthlySavingsGoal) {
        this.monthlySavingsGoal = monthlySavingsGoal;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public void setThemePreference(ThemePreference themePreference) {
        this.themePreference = themePreference;
    }

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

    public static User newStudent(String email, String passwordHash, String fullName, String currency) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.fullName = fullName;
        user.role = UserRole.STUDENT;
        user.status = AccountStatus.ACTIVE;
        user.currency = currency;
        user.tokenVersion = 0;

        user.academicYear = null;
        user.monthlyAllowanceBaseline = BigDecimal.ZERO;
        user.monthlySavingsGoal = BigDecimal.ZERO;

        user.themePreference = ThemePreference.SYSTEM;
        user.fontScale = FontScale.MEDIUM;

        return user;
    }
}
