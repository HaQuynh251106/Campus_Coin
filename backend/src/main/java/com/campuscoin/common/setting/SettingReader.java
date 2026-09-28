package com.campuscoin.common.setting;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.common.setting.repository.SystemSettingRepository;

@Component
public class SettingReader {

    private static final Logger log = LoggerFactory.getLogger(SettingReader.class);

    public static final String AUTH_SESSION_TTL_MINUTES = "auth.session_ttl_minutes";

    public static final String AUTH_MAX_LOGIN_ATTEMPTS = "auth.max_login_attempts";

    public static final String AUTH_RESET_TOKEN_TTL_MINUTES = "auth.reset_token_ttl_minutes";

    public static final String APP_CURRENCY = "app.currency";

    public static final String ANOMALY_DUPLICATE_WINDOW_DAYS = "anomaly.duplicate_window_days";

    public static final String ANOMALY_UNUSUAL_MULTIPLIER = "anomaly.unusual_multiplier";

    public static final String TIPS_MAX_DASHBOARD = "tips.max_dashboard";

    public static final int DEFAULT_TIPS_MAX_DASHBOARD = 3;

    public static final String BUDGET_NEAR_THRESHOLD_PCT = "budget.near_threshold_pct";

    public static final String BUDGET_EXCEEDED_THRESHOLD_PCT = "budget.exceeded_threshold_pct";

    public static final int DEFAULT_BUDGET_NEAR_THRESHOLD_PCT = 80;

    public static final int DEFAULT_BUDGET_EXCEEDED_THRESHOLD_PCT = 100;

    private final SystemSettingRepository repository;

    public SettingReader(SystemSettingRepository repository) {
        this.repository = repository;
    }

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

                log.warn("Setting {} has a non-positive value; using default {}", key, defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            log.warn("Setting {} is not a valid integer; using default {}", key, defaultValue);
            return defaultValue;
        }
    }

    @Transactional(readOnly = true)
    public String getString(String key, String defaultValue) {
        return repository.findBySettingKey(key)
                .map(setting -> setting.getSettingValue())
                .filter(value -> !value.isBlank())
                .orElse(defaultValue);
    }

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
