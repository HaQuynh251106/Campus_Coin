package com.campuscoin.admin.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.RequestValidationException;

/**
 * The keys {@code sp_admin_set_threshold} will change, and the shape each value must have.
 *
 * <p>A separate type from the service for the reason {@code BookmarkWriteFailure} is: the decision it
 * makes is the part that has to be right, and this way it can be read - and tested - on its own
 * rather than being one branch among many in a service method.
 *
 * <p><b>It exists twice over, which is the point.</b> {@code GET /api/v1/admin/settings} uses it to
 * mark each row {@code adjustable}, and {@code PATCH .../settings/{key}} uses it to decide whether a
 * change is allowed and whether the value fits. Both questions are answered from this one map, so a
 * key cannot be advertised as changeable while being refused, or refused while being advertised.
 *
 * <p><b>Why the service validates rather than letting the procedure refuse.</b> The procedure checks
 * its three conditions in an order that separates them from the caller's point of view only at
 * runtime: the key must be on its allow-list, then the value must fit the key's shape, and only then
 * is the key looked up in {@code system_settings}. The first two refusals are the ones an API caller
 * can provoke, and every refusal it raises carries SQLSTATE 45000, so "which of my inputs was wrong"
 * is not answerable from the exception. A caller who sends {@code ninety} to a percentage key would
 * get the procedure's refusal describing the value; a caller who sends {@code 90} to {@code app.timezone}
 * would get one describing the key. Validating both here, in that order, means the procedure's
 * refusals reach the client already labelled.
 *
 * <p>The third branch - a key that is on the allow-list but absent from {@code system_settings} - is
 * not reachable through this API. All six rows are seeded by {@code db/05_seed.sql}, so an adjustable
 * key always exists; and a non-adjustable key is refused here before the call, which is what keeps
 * this true. Java therefore does not pre-check existence, because a check whose premise is a seeded
 * row would be the kind of restatement that drifts.
 *
 * <p><b>The six keys are exactly the procedure's list, and all six are adjustable.</b> Two of them -
 * {@code insight.spike_threshold_pct} and {@code insight.spike_baseline_months} - carry an
 * {@code insight.} prefix, and an earlier plan for this module came close to refusing them by name on
 * the assumption that the prefix meant UC-17 and therefore locked module 12. That was wrong and the
 * correction is worth recording, because the mistake is a plausible one: {@code sp_generate_tips}
 * reads {@code insight.spike_threshold_pct} when it decides BR-15's category-spike tip, and
 * {@code v_category_spend_trend} joins both keys, so they tune behaviour that shipped in module 9 and
 * is covered by {@code TipsRuleCoverageIT}. Module 12's own surfaces are the {@code insights} table
 * and {@code sp_generate_monthly_insight}; neither has a key here. Locking these two would have left
 * a live threshold permanently untunable, which is what VĐ-05 exists to prevent.
 *
 * <p>The shapes are the procedure's own rules, stated once. Every key takes a positive number; the
 * baseline month count is the one that must additionally be a whole number in 1..12, which the
 * procedure enforces separately - see the comment there about BR-15's three-month convention.
 *
 * <p><b>Public, unlike the other classification helpers in this package.</b>
 * {@code AdminSettingsMapper} publishes this allow-list's answer as each row's {@code adjustable}
 * flag, and it lives in the {@code mapper} package where that decision belongs. Keeping the list
 * package-private would have meant moving the mapper into {@code service} - hiding the disclosure
 * decision in a package it does not belong to - or passing the flag in from outside, which would put
 * the same question in two places. Widening this one type is the smaller cost, and nothing outside
 * {@code com.campuscoin.admin} uses it.
 */
public final class AdminThresholds {

    /** {@code budget.near_threshold_pct}: the share of a limit at which a near-budget tip is raised. */
    private static final String BUDGET_NEAR = "budget.near_threshold_pct";

    /** {@code budget.exceeded_threshold_pct}: the share of a limit that counts as exceeded. */
    private static final String BUDGET_EXCEEDED = "budget.exceeded_threshold_pct";

    /** BR-15: how far above its baseline a category's spending must rise to be a spike. */
    private static final String INSIGHT_SPIKE_THRESHOLD = "insight.spike_threshold_pct";

    /** BR-15: how many months of history the spike baseline is computed over. */
    private static final String INSIGHT_SPIKE_BASELINE_MONTHS = "insight.spike_baseline_months";

    /** How many tips the dashboard shows at once. */
    private static final String TIPS_MAX_DASHBOARD = "tips.max_dashboard";

    /** BR-04: how long an issued password reset link stays usable. */
    private static final String AUTH_RESET_TOKEN_TTL = "auth.reset_token_ttl_minutes";

    /**
     * The allowed keys, each mapped to its value shape.
     *
     * <p>Insertion-ordered so the settings list is stable between calls: a map that reordered its keys
     * would make two identical requests return the same rows in different orders, which is the same
     * defect {@code v_admin_top_categories} has and the DAO corrects with an explicit order.
     */
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

    /**
     * Whether this key can be changed at all through the API.
     *
     * <p>Read by the settings mapper to set each row's {@code adjustable} flag and by the service to
     * decide whether a change is allowed, so the advertised answer and the enforced one cannot differ.
     * A key not in the map is not adjustable; null is not adjustable either, which answers the
     * "no such key" case the same way as "not changeable" without a separate branch.
     */
    public static boolean isAdjustable(String key) {
        return key != null && ALLOWED.containsKey(key);
    }

    /**
     * Checks a value against the key's shape, or explains why it does not fit.
     *
     * <p>The caller has already established that the key is adjustable; this answers the second half
     * of the question. The message names the field and states the rule the procedure enforces, in the
     * caller's terms - a field error the client can render beside the input, rather than a generic
     * conflict.
     *
     * @throws RequestValidationException when the value is not a number, is not positive, or is not a
     *                                    whole number in the range this key requires
     */
    static void assertValueFits(String key, String value) {
        ValueShape shape = ALLOWED.get(key);
        if (shape == null) {
            // Not reachable through the service, which checks isAdjustable first. Thrown rather than
            // ignored so that a future caller who forgets cannot silently skip validation.
            throw new IllegalArgumentException("No value shape is known for setting " + key);
        }

        String trimmed = value == null ? "" : value.trim();
        BigDecimal number = parse(trimmed);

        if (shape == ValueShape.WHOLE_NUMBER_1_TO_12) {
            // The range and the whole-number requirement are one rule here, matching the procedure's
            // single branch for this key: a value of 0.5 or 13 is refused by the same check it makes.
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

    /**
     * The value as a number, or {@code null} when it is not one.
     *
     * <p>A failed parse and a missing value are the same answer here because the caller's remedy is
     * the same and the message already covers both. {@code BigDecimal} rather than {@code double} so
     * that a value like {@code 0.1} is read exactly - the procedure casts to {@code DECIMAL(10,4)},
     * and comparing through a binary floating-point type could disagree with it at the margins.
     */
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

    /** The value a key accepts. Two shapes, between them covering all six keys. */
    private enum ValueShape {
        /** Any positive decimal, as the procedure's {@code v_num <= 0} check requires. */
        POSITIVE_NUMBER,
        /** A whole number from 1 to 12 inclusive, this key's own additional rule. */
        WHOLE_NUMBER_1_TO_12
    }
}
