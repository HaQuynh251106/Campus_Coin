package com.campuscoin.tips;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The four generation rules {@code TipsApiIT} does not reach: {@code OVER_BUDGET},
 * {@code NEAR_BUDGET}, {@code CATEGORY_SPIKE} and {@code SAVINGS_GOAL_AT_RISK}.
 *
 * <p><b>Why this suite exists.</b> {@code TipsApiIT} drives rule 4 (an unbudgeted category) and rule
 * 6 (a month with no records), because those are reachable from one transaction. Rules 1, 2, 3 and 5
 * each need something more - a limit set against spending that already exists, an earlier month to
 * compare against, or a savings goal - and a module that binds UC-18 to the API cannot leave the
 * rules behind most of the tips a student actually sees unexercised. The fixtures that build those
 * preconditions use the budgets and profile endpoints, which are public routes, so nothing here
 * reaches past the API into the database to write what the API could not.
 *
 * <p><b>What is asserted and what is not.</b> These tests hold the API to the <em>rule</em> that
 * produced a tip - read from {@code user_tips.tip_template_id}, the database's own statement of which
 * rule fired, and the value the uniqueness key is built from. Two things are deliberately not
 * asserted here:
 *
 * <ul>
 *   <li><b>The rendered text.</b> Matching a tip's title would assert {@code fn_render_template}'s
 *       output and the template's wording, which this module neither owns nor may restate. The one
 *       exception is {@code potentialSaving}, which <em>is</em> published and is asserted - it is the
 *       rule's arithmetic, and a test that did not check it would not be checking the figure a
 *       student is shown.
 *   <li><b>Which tips win the ranking.</b> Ranking is BR-14's and is asserted as a <em>bound</em>
 *       (test 10), not as a predicted order: predicting it would mean recomputing every rule's
 *       {@code rank_score}, which is the thing this module must not duplicate in Java.
 * </ul>
 *
 * <p>The thresholds the rules compare against are {@code system_settings} values, and these tests do
 * not change them: a test that moved a threshold would be asserting its own edit. The figures used
 * below are chosen to sit exactly on or just inside a band, so what is being tested is the boundary
 * the procedure applies to the setting the schema already holds.
 */
class TipsRuleCoverageIT extends AbstractTipsApiIT {

    private static final String OVER_BUDGET = "OVER_BUDGET";
    private static final String NEAR_BUDGET = "NEAR_BUDGET";
    private static final String CATEGORY_SPIKE = "CATEGORY_SPIKE";
    private static final String NO_BUDGET_SET = "NO_BUDGET_SET";
    private static final String SAVINGS_GOAL_AT_RISK = "SAVINGS_GOAL_AT_RISK";

    // ==================================================================
    //  Rules 1 and 2 — over the limit, and approaching it
    // ==================================================================

    @Test
    @DisplayName("UC-18/BR-12: spending on the exceeded threshold produces the over-budget tip only")
    void spendingOnTheExceededThresholdProducesOnlyTheOverBudgetTip() throws Exception {
        String token = loginNewStudent();
        Long food = defaultCategoryId(FOOD);

        // Exactly at the limit: 30.00 spent against a 30.00 limit is 100%, which is
        // budget.exceeded_threshold_pct. Rule 1's band starts at the threshold and rule 2's ends
        // below it, so exactly one of the two can fire - never both, and never neither.
        spendInCategory(token, food, "30.00", thisMonth());
        setBudget(token, food, "30.00", thisMonth());

        JsonNode response = body(generateTipsViaApi(token));

        assertThat(templateCodesOf(response))
                .as("at exactly the exceeded threshold only OVER_BUDGET applies")
                .containsExactly(OVER_BUDGET);
        JsonNode tip = response.get("tips").get(0);
        assertThat(tip.get("categoryId").asLong()).isEqualTo(food);
        // Spending equals the limit, so there is nothing left to recover: the rule's
        // GREATEST(spent - limit, 0) is zero and the published figure says so rather than being
        // omitted. A tip can be advice with no figure attached.
        assertThat(tip.get("potentialSaving").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("UC-18/BR-12: spending on the near threshold produces the near-budget tip, not the over one")
    void spendingOnTheNearThresholdProducesTheNearBudgetTip() throws Exception {
        String token = loginNewStudent();
        Long food = defaultCategoryId(FOOD);

        // 24.00 against 30.00 is exactly 80%, which is budget.near_threshold_pct - the lower edge of
        // rule 2's band. The same figure the seeded demo month holds for Food, so this asserts the
        // boundary the seed happens to sit on.
        spendInCategory(token, food, "24.00", thisMonth());
        setBudget(token, food, "30.00", thisMonth());

        JsonNode response = body(generateTipsViaApi(token));

        assertThat(templateCodesOf(response))
                .as("on the near threshold the approaching tip applies and the over-limit one does not")
                .containsExactly(NEAR_BUDGET);
        JsonNode tip = response.get("tips").get(0);
        assertThat(tip.get("categoryId").asLong()).isEqualTo(food);
        // Rule 2's figure is half of what is left: (30.00 - 24.00) * 0.5. Published unchanged, so
        // this checks the API passes the rule's arithmetic through rather than recomputing it.
        assertThat(tip.get("potentialSaving").decimalValue()).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("UC-18: a published saving carries the column's two decimals on the wire")
    void aPublishedSavingCarriesTwoDecimalsOnTheWire() throws Exception {
        String token = loginNewStudent();
        Long transport = defaultCategoryId(TRANSPORT);
        spendInCategory(token, transport, "40.00", thisMonth());

        // Asserted against the raw body, not a parsed node: this suite's ObjectMapper parses JSON
        // numbers into doubles, so a parsed 8.00 and a parsed 8.0 are indistinguishable and a
        // node-level assertion could not see the difference. The published scale is part of the
        // contract - potential_saving is DECIMAL(15,2), and a client rendering "8" instead of
        // "8.00" in a currency field is the kind of drift a contract test exists to catch. The
        // figure is rule 4's own arithmetic for Transport, 40.00 * 0.2.
        String raw = generateTipsViaApi(token).getBody();

        assertThat(raw).contains("\"potentialSaving\":8.00");
    }

    @Test
    @DisplayName("UC-18: a category inside the near band never produces both budget tips at once")
    void approachingTheLimitNeverProducesBothBudgetTips() throws Exception {
        String token = loginNewStudent();
        Long food = defaultCategoryId(FOOD);

        // 25.00 against 30.00 is 83.3%, inside [near, exceeded). The two rules share one band edge
        // with sp_check_budget_alerts, so a tip and an alert about this category always agree about
        // which side of the limit it is on - and exactly one tip is produced, not one per rule.
        spendInCategory(token, food, "25.00", thisMonth());
        setBudget(token, food, "30.00", thisMonth());

        JsonNode response = body(generateTipsViaApi(token));

        assertThat(templateCodesOf(response)).containsExactly(NEAR_BUDGET);
        assertThat(templateCodesOf(response)).doesNotContain(OVER_BUDGET);
        assertThat(countOf("SELECT COUNT(*) FROM user_tips WHERE user_id = ? AND category_id = ?",
                userIdOf(token), food))
                .as("one category inside one band produces one tip, not one per rule")
                .isEqualTo(1);
        assertThat(response.get("tips").get(0).get("potentialSaving").decimalValue())
                .isEqualByComparingTo("2.50");
    }

    // ==================================================================
    //  Rule 3 — a category rising against the student's own history
    // ==================================================================

    @Test
    @DisplayName("UC-18/BR-15: a rise against a single earlier month is enough to produce the spike tip")
    void aRiseAgainstASingleEarlierMonthProducesTheSpikeTip() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long transport = defaultCategoryId(TRANSPORT);
        LocalDate lastMonth = monthBefore(thisMonth());

        spendInCategory(token, transport, "10.00", lastMonth);
        spendInCategory(token, transport, "40.00", thisMonth());

        JsonNode response = body(generateTipsViaApi(token));

        assertThat(templateCodesOf(response))
                .as("a category that rose against the student's own earlier month spikes")
                .contains(CATEGORY_SPIKE);
        // The gate the procedure applies is baseline_months >= 1, not the setting's window of 3 -
        // insight.spike_baseline_months sizes the average, it does not require three months to exist.
        // Asserted here because it is the fact that makes this case reachable at all, and because a
        // reader who assumed the window was the gate would expect no tip.
        assertThat(longValuesFrom("SELECT baseline_months FROM v_category_spend_trend "
                        + "WHERE user_id = ? AND category_id = ? AND current_month = ?",
                userId, transport, thisMonth()))
                .as("one earlier month satisfies the procedure's baseline gate")
                .containsExactly(1L);
    }

    @Test
    @DisplayName("UC-18/BR-15: a category with no earlier spending produces no spike tip")
    void aCategoryWithNoEarlierSpendingProducesNoSpikeTip() throws Exception {
        String token = loginNewStudent();
        Long transport = defaultCategoryId(TRANSPORT);

        // Spending in this month alone. The view has no earlier month to average, so its baseline
        // average is zero and the procedure requires it to be above zero: a category the student has
        // never bought before is new, not "up", and telling them it rose would be inventing a
        // comparison. The unbudgeted-category tip still fires, so the month is not silent.
        spendInCategory(token, transport, "40.00", thisMonth());

        JsonNode response = body(generateTipsViaApi(token));

        assertThat(templateCodesOf(response)).contains(NO_BUDGET_SET);
        assertThat(templateCodesOf(response))
                .as("a first purchase is not a rise")
                .doesNotContain(CATEGORY_SPIKE);
    }

    // ==================================================================
    //  Rule 5 — the savings goal
    // ==================================================================

    @Test
    @DisplayName("UC-18/VĐ-04: a month whose net falls below the goal produces the savings-goal tip")
    void aMonthWhoseNetFallsBelowTheGoalProducesTheSavingsGoalTip() throws Exception {
        String token = loginNewStudent();
        Long allowance = defaultCategoryId(INCOME_CATEGORY);

        spendInCategory(token, allowance, "50.00", thisMonth());
        setSavingsGoal(token, "100.00");

        JsonNode response = body(generateTipsViaApi(token));

        assertThat(templateCodesOf(response))
                .as("income of 50.00 against a goal of 100.00 leaves the goal at risk")
                .containsExactly(SAVINGS_GOAL_AT_RISK);
        JsonNode tip = response.get("tips").get(0);
        // The figure is the shortfall itself: goal - net, which is 100.00 - 50.00. The row is the
        // proof the API publishes the rule's own arithmetic.
        assertThat(tip.get("potentialSaving").decimalValue()).isEqualByComparingTo("50.00");
        // This tip is about no single category, so the field is absent from the JSON rather than
        // present and null - a client links to a category when there is one and omits the link when
        // there is not.
        assertThat(fieldNamesOf(tip)).doesNotContain("categoryId");
    }

    @Test
    @DisplayName("UC-18/VĐ-04: a month whose net meets the goal produces no savings-goal tip")
    void aMonthWhoseNetMeetsTheGoalProducesNoSavingsGoalTip() throws Exception {
        String token = loginNewStudent();
        Long allowance = defaultCategoryId(INCOME_CATEGORY);

        spendInCategory(token, allowance, "50.00", thisMonth());
        setSavingsGoal(token, "10.00");

        JsonNode response = body(generateTipsViaApi(token));

        // The goal is met, so there is nothing to warn about. A goal of zero is not a goal either -
        // the procedure requires it to be above zero - so a student who has not set one is never
        // told their savings are at risk.
        assertThat(templateCodesOf(response)).doesNotContain(SAVINGS_GOAL_AT_RISK);
    }

    @Test
    @DisplayName("UC-18: the savings-goal tip and an unbudgeted-category tip coexist in one month")
    void theSavingsGoalTipAndAnUnbudgetedCategoryTipCoexist() throws Exception {
        String token = loginNewStudent();
        Long allowance = defaultCategoryId(INCOME_CATEGORY);
        Long transport = defaultCategoryId(TRANSPORT);

        spendInCategory(token, allowance, "50.00", thisMonth());
        spendInCategory(token, transport, "40.00", thisMonth());
        setSavingsGoal(token, "100.00");

        JsonNode response = body(generateTipsViaApi(token));

        // Two different rules about two different facts: the month's net is short of the goal
        // (50.00 - 40.00 = 10.00 against 100.00) and a heavy category has no limit. Each is a
        // separate row with its own template, which is what the uniqueness key distinguishes.
        assertThat(templateCodesOf(response))
                .containsExactlyInAnyOrder(SAVINGS_GOAL_AT_RISK, NO_BUDGET_SET);
    }

    // ==================================================================
    //  Rule 4's cap, and BR-14's bound on the stored set
    // ==================================================================

    @Test
    @DisplayName("UC-18: the unbudgeted-category rule keeps the two largest categories, not all of them")
    void ruleFourIsCappedAtTheTwoLargestUnbudgetedCategories() throws Exception {
        String token = loginNewStudent();
        Long food = defaultCategoryId(FOOD);
        Long transport = defaultCategoryId(TRANSPORT);
        Long entertainment = defaultCategoryId(ENTERTAINMENT);

        spendInCategory(token, food, "60.00", thisMonth());
        spendInCategory(token, transport, "30.00", thisMonth());
        spendInCategory(token, entertainment, "20.00", thisMonth());

        JsonNode response = body(generateTipsViaApi(token));

        // Rule 4 has its own LIMIT 2, so three unbudgeted expense categories produce two tips about
        // the two largest. A list of every unbudgeted category would be a category list, not advice.
        List<String> codes = templateCodesOf(response);
        assertThat(codes).as("rule 4's own cap holds").hasSize(2).containsOnly(NO_BUDGET_SET);
        assertThat(amountOf(response, food))
                .as("the largest category is the one rule 4 keeps")
                .isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("UC-18/BR-14: the top-N bound comes from the parameter the caller passes, and the "
            + "endpoint passes none so the setting decides")
    void theStoredTipCountIsBoundedByTheSettingsTopN() throws Exception {
        // The same four candidate tips, generated two ways. The endpoint passes no max_tips, so the
        // procedure falls back to tips.max_dashboard in system_settings; a caller that passes a
        // number gets that number instead. Comparing the two shows the bound is the parameter and
        // not something the application imposes - which is VĐ-05: a threshold comes from a setting,
        // not from code.
        String viaEndpoint = loginNewStudent();
        aMonthWithFourCandidateRules(viaEndpoint, thisMonth());
        JsonNode response = body(generateTipsViaApi(viaEndpoint));

        String viaExplicitCount = loginNewStudent();
        Long secondUserId = userIdOf(viaExplicitCount);
        aMonthWithFourCandidateRules(viaExplicitCount, thisMonth());
        generateTipsFor(secondUserId, thisMonth(), 5);

        assertThat(templateCodesOf(response))
                .as("the endpoint's bound is the seeded tips.max_dashboard setting")
                .hasSize(3);
        assertThat(tipIdsFor(secondUserId, thisMonth()))
                .as("the same candidates with max_tips 5 are all stored")
                .hasSize(4);
        assertThat(templateCodesOf(response))
                .as("the bound keeps the highest-ranked tips, not an arbitrary three")
                .contains(SAVINGS_GOAL_AT_RISK);
    }

    // ==================================================================
    //  Helpers
    // ==================================================================

    /**
     * Builds a month holding exactly four candidate tips, one from each of three rules.
     *
     * <p>Four rather than three so that a bound of three is distinguishable from no bound at all -
     * with exactly three candidates, a stored count of three would prove nothing.
     */
    private void aMonthWithFourCandidateRules(String token, LocalDate month) throws Exception {
        Long food = defaultCategoryId(FOOD);
        Long allowance = defaultCategoryId(INCOME_CATEGORY);
        Long transport = defaultCategoryId(TRANSPORT);
        Long entertainment = defaultCategoryId(ENTERTAINMENT);

        // Rule 1: past its limit. The limit is set after the spending on purpose, so the month
        // already holds more than the limit and the category is over it.
        spendInCategory(token, food, "50.00", month);
        setBudget(token, food, "30.00", month);
        // Rule 4: two heavy categories with no limit between them - already rule 4's own cap.
        spendInCategory(token, transport, "40.00", month);
        spendInCategory(token, entertainment, "30.00", month);
        // Rule 5: income below the goal, and below the month's spending.
        spendInCategory(token, allowance, "10.00", month);
        setSavingsGoal(token, "100.00");
    }

    /** The published {@code potentialSaving} of the tip about a category, or {@code null}. */
    private static java.math.BigDecimal amountOf(JsonNode listResponse, Long categoryId) {
        for (JsonNode tip : listResponse.get("tips")) {
            JsonNode category = tip.get("categoryId");
            if (category != null && category.asLong() == categoryId) {
                return tip.get("potentialSaving").decimalValue();
            }
        }
        return null;
    }
}
