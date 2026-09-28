package com.campuscoin.admin.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.RequestValidationException;

public final class AdminThresholds {

    private static final String BUDGET_NEAR = "budget.near_threshold_pct";

    private static final String BUDGET_EXCEEDED = "budget.exceeded_threshold_pct";

    private static final String INSIGHT_SPIKE_THRESHOLD = "insight.spike_threshold_pct";

    private static final String INSIGHT_SPIKE_BASELINE_MONTHS = "insight.spike_baseline_months";

    private static final String TIPS_MAX_DASHBOARD = "tips.max_dashboard";

    private static final String AUTH_RESET_TOKEN_TTL = "auth.reset_token_ttl_minutes";

    private static final Map<String, ValueShape> ALLOWED = allowedKeys();

    private AdminThresholds() {
    }

    private static Map<String, ValueShape> allowedKeys() {
        Map<String, ValueShape> keys = new LinkedHashMap<>();
        keys.put(BUDGET_NEAR, ValueShape.POSITIVE_NUMBER);
        keys.put(BUDGET_EXCEEDED, ValueShape.POSITIVE_NUMBER);
        keys.put(INSIGHT_SPIKE_THRESHOLD, ValueShape.POSITIVE_NUMBER);
        keys.put(INSIGHT_SPIKE_BASELINE_MONTHS, ValueShape.WHOLE_NUMBER_1_TO_12);
        keys.put(TIPS_MAX_DASHBOARD, ValueShape.POSITIVE_NUMBER);
        keys.put(AUTH_RESET_TOKEN_TTL, ValueShape.POSITIVE_NUMBER);
        return Map.copyOf(keys);
    }

    public static boolean isAdjustable(String key) {
        return key != null && ALLOWED.containsKey(key);
    }

    static void assertValueFits(String key, String value) {
        ValueShape shape = ALLOWED.get(key);
        if (shape == null) {

            throw new IllegalArgumentException("No value shape is known for setting " + key);
        }

        String trimmed = value == null ? "" : value.trim();
        BigDecimal number = parse(trimmed);

        if (shape == ValueShape.WHOLE_NUMBER_1_TO_12) {

            if (number == null || number.compareTo(BigDecimal.ONE) < 0
                    || number.compareTo(BigDecimal.valueOf(12)) > 0
                    || number.stripTrailingZeros().scale() > 0) {
                throw new RequestValidationException("Request validation failed.",
                        List.of(new ApiError.FieldError("value",
                                "This setting must be a whole number from 1 to 12.")));
            }
            return;
        }

        if (number == null || number.signum() <= 0) {
            throw new RequestValidationException("Request validation failed.",
                    java.util.List.of(new ApiError.FieldError("value",
                            "This setting must be a positive number.")));
        }
    }

    private static BigDecimal parse(String trimmed) {
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private enum ValueShape {

        POSITIVE_NUMBER,

        WHOLE_NUMBER_1_TO_12
    }
}
