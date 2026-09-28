package com.campuscoin.tips;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

class TipsRuleCoverageIT extends AbstractTipsApiIT {

    private static final String OVER_BUDGET = "OVER_BUDGET";
    private static final String NEAR_BUDGET = "NEAR_BUDGET";
    private static final String CATEGORY_SPIKE = "CATEGORY_SPIKE";
    private static final String NO_BUDGET_SET = "NO_BUDGET_SET";
    private static final String SAVINGS_GOAL_AT_RISK = "SAVINGS_GOAL_AT_RISK";

    @Test
    @DisplayName("UC-18/BR-12: spending on the exceeded threshold produces the over-budget tip only")
    void spendingOnTheExceededThresholdProducesOnlyTheOverBudgetTip() throws Exception {
        String token = loginNewStudent();
        Long food = defaultCategoryId(FOOD);

        spendInCategory(token, food, "30.00", thisMonth());
        setBudget(token, food, "30.00", thisMonth());

        JsonNode response = body(generateTipsViaApi(token));

        assertThat(templateCodesOf(response))
                .as("at exactly the exceeded threshold only OVER_BUDGET applies")
                .containsExactly(OVER_BUDGET);
        JsonNode tip = response.get("tips").get(0);
        assertThat(tip.get("categoryId").asLong()).isEqualTo(food);

        assertThat(tip.get("potentialSaving").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("UC-18/BR-12: spending on the near threshold produces the near-budget tip, not the over one")
    void spendingOnTheNearThresholdProducesTheNearBudgetTip() throws Exception {
        String token = loginNewStudent();
        Long food = defaultCategoryId(FOOD);

        spendInCategory(token, food, "24.00", thisMonth());
        setBudget(token, food, "30.00", thisMonth());

        JsonNode response = body(generateTipsViaApi(token));

        assertThat(templateCodesOf(response))
                .as("on the near threshold the approaching tip applies and the over-limit one does not")
                .containsExactly(NEAR_BUDGET);
        JsonNode tip = response.get("tips").get(0);
        assertThat(tip.get("categoryId").asLong()).isEqualTo(food);

        assertThat(tip.get("potentialSaving").decimalValue()).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("UC-18: a published saving carries the column's two decimals on the wire")
    void aPublishedSavingCarriesTwoDecimalsOnTheWire() throws Exception {
        String token = loginNewStudent();
        Long transport = defaultCategoryId(TRANSPORT);
        spendInCategory(token, transport, "40.00", thisMonth());

        String raw = generateTipsViaApi(token).getBody();

        assertThat(raw).contains("\"potentialSaving\":8.00");
    }

    @Test
    @DisplayName("UC-18: a category inside the near band never produces both budget tips at once")
    void approachingTheLimitNeverProducesBothBudgetTips() throws Exception {
        String token = loginNewStudent();
        Long food = defaultCategoryId(FOOD);

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

        spendInCategory(token, transport, "40.00", thisMonth());

        JsonNode response = body(generateTipsViaApi(token));

        assertThat(templateCodesOf(response)).contains(NO_BUDGET_SET);
        assertThat(templateCodesOf(response))
                .as("a first purchase is not a rise")
                .doesNotContain(CATEGORY_SPIKE);
    }

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

        assertThat(tip.get("potentialSaving").decimalValue()).isEqualByComparingTo("50.00");

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

        assertThat(templateCodesOf(response))
                .containsExactlyInAnyOrder(SAVINGS_GOAL_AT_RISK, NO_BUDGET_SET);
    }

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

    private void aMonthWithFourCandidateRules(String token, LocalDate month) throws Exception {
        Long food = defaultCategoryId(FOOD);
        Long allowance = defaultCategoryId(INCOME_CATEGORY);
        Long transport = defaultCategoryId(TRANSPORT);
        Long entertainment = defaultCategoryId(ENTERTAINMENT);

        spendInCategory(token, food, "50.00", month);
        setBudget(token, food, "30.00", month);

        spendInCategory(token, transport, "40.00", month);
        spendInCategory(token, entertainment, "30.00", month);

        spendInCategory(token, allowance, "10.00", month);
        setSavingsGoal(token, "100.00");
    }

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
