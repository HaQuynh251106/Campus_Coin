package com.campuscoin.common.setting;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.common.setting.repository.SystemSettingRepository;

/**
 * Reads business thresholds from {@code system_settings}.
 *
 * <p>VĐ-05 makes these values configuration rather than constants: an administrator can change
 * them, and changing one must change behaviour without a redeploy. The database procedures
 * already read them directly ({@code sp_create_password_reset_token} reads the reset TTL), and
 * this class exists so Java code obeys the same rule instead of hard-coding a number.
 *
 * <p>Every lookup therefore has the same shape as the SQL: read the key, and if it is missing or
 * unusable fall back to the documented default. The fallback repeats the {@code IFNULL(...)}
 * guards used by the views and procedures, so Java and SQL always agree on the effective value.
 */
@Component
public class SettingReader {

    private static final Logger log = LoggerFactory.getLogger(SettingReader.class);

    /** UC-02 B3: how long one issued session stays valid. Mirrors the seeded default. */
    public static final String AUTH_SESSION_TTL_MINUTES = "auth.session_ttl_minutes";

    /** Section 7.10: failed sign-ins allowed before a temporary lock. */
    public static final String AUTH_MAX_LOGIN_ATTEMPTS = "auth.max_login_attempts";

    /** BR-04: password reset token lifetime, also read by the database procedure. */
    public static final String AUTH_RESET_TOKEN_TTL_MINUTES = "auth.reset_token_ttl_minutes";

    /** VĐ-08: the currency a new account starts with, also used by the dashboard. */
    public static final String APP_CURRENCY = "app.currency";

    /**
     * UC-24: how many days back a record is compared against when looking for a suspected duplicate.
     *
     * <p>Seeded at {@code 3}. Read here rather than written into the detector because VĐ-05 makes it
     * configuration: an administrator retunes the window without a redeploy. The value was seeded for
     * the anomaly feature before that feature existed, which is why the key is already there.
     */
    public static final String ANOMALY_DUPLICATE_WINDOW_DAYS = "anomaly.duplicate_window_days";

    /**
     * UC-24: how many times the student's own average a record must reach to count as unusual.
     *
     * <p>Seeded at {@code 3}. A decimal rather than an integer, because the column is
     * {@code DECIMAL} and a multiplier such as {@code 2.5} is a legitimate retuning.
     */
    public static final String ANOMALY_UNUSUAL_MULTIPLIER = "anomaly.unusual_multiplier";

    private final SystemSettingRepository repository;

    public SettingReader(SystemSettingRepository repository) {
        this.repository = repository;
    }

    /**
     * Reads an integer setting.
     *
     * @param key          the {@code setting_key} to read
     * @param defaultValue returned when the row is absent, or its value is not a positive integer
     */
    @Transactional(readOnly = true)
    public int getInt(String key, int defaultValue) {
        String raw = repository.findBySettingKey(key)
                .map(setting -> setting.getSettingValue())
                .orElse(null);

        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed <= 0) {
                // A zero or negative lifetime / attempt limit would disable the control it
                // configures, so it is treated as unusable rather than obeyed.
                log.warn("Setting {} has a non-positive value; using default {}", key, defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            log.warn("Setting {} is not a valid integer; using default {}", key, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Reads a text setting.
     *
     * @param key          the {@code setting_key} to read
     * @param defaultValue returned when the row is absent or its value is blank
     */
    @Transactional(readOnly = true)
    public String getString(String key, String defaultValue) {
        return repository.findBySettingKey(key)
                .map(setting -> setting.getSettingValue())
                .filter(value -> !value.isBlank())
                .orElse(defaultValue);
    }

    /**
     * Reads a decimal setting.
     *
     * <p>The counterpart of {@link #getInt} for a key whose column is {@code DECIMAL} - a multiplier
     * such as {@code 2.5} is a legitimate retuning, so an integer reader would silently discard it.
     * The same unusable-value policy applies, for the same reason: a zero or negative multiplier
     * would make every record "unusual" and a zero-day window would make the duplicate check
     * useless, so a value that would disable the control it configures is treated as absent and the
     * documented default is used instead. It mirrors the {@code IFNULL(NULLIF(CAST(...), 0), default)}
     * guards the views apply for the same class of key (VĐ-05).
     *
     * @param key          the {@code setting_key} to read
     * @param defaultValue returned when the row is absent, or its value is not a positive decimal
     */
    @Transactional(readOnly = true)
    public BigDecimal getDecimal(String key, BigDecimal defaultValue) {
        String raw = repository.findBySettingKey(key)
                .map(setting -> setting.getSettingValue())
                .orElse(null);

        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            BigDecimal parsed = new BigDecimal(raw.trim());
            if (parsed.signum() <= 0) {
                log.warn("Setting {} has a non-positive value; using default {}", key, defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            log.warn("Setting {} is not a valid decimal; using default {}", key, defaultValue);
            return defaultValue;
        }
    }
}
