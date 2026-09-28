package com.campuscoin.tips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TipsStateConsistencyIT extends AbstractTipsApiIT {

    @Test
    @DisplayName("ck_tip_state: a pinned tip without a pinned time is refused by the database")
    void pinningWithoutATimestampIsRefusedByTheSchema() throws Exception {
        String token = loginNewStudent();
        Long tipId = aGeneratedTip(token);

        assertThatThrownBy(() -> runInDatabase(
                "UPDATE user_tips SET state = 'PINNED' WHERE id = ?", tipId))
                .as("the schema must refuse a pinned tip that carries no pinned_at")
                .isInstanceOf(SQLException.class);

        assertThat(tipStateOf(tipId)).isEqualTo("NEW");
    }

    @Test
    @DisplayName("ck_tip_state: a pinned tip carrying a dismissed time is refused by the database")
    void pinningWithAStaleDismissalTimestampIsRefused() throws Exception {
        String token = loginNewStudent();
        Long tipId = aGeneratedTip(token);

        assertThatThrownBy(() -> runInDatabase(
                "UPDATE user_tips SET state = 'PINNED', dismissed_at = NOW() WHERE id = ?", tipId))
                .as("the schema must refuse a pinned tip that carries a dismissed_at")
                .isInstanceOf(SQLException.class);

        assertThat(tipStateOf(tipId)).isEqualTo("NEW");
    }

    @Test
    @DisplayName("ck_tip_state: a NEW tip carrying either timestamp is refused by the database")
    void aNewTipCarryingATimestampIsRefused() throws Exception {
        String token = loginNewStudent();
        Long tipId = aGeneratedTip(token);

        for (String column : new String[] {"pinned_at", "dismissed_at"}) {
            assertThatThrownBy(() -> runInDatabase(
                    "UPDATE user_tips SET state = 'NEW', " + column + " = NOW() WHERE id = ?", tipId))
                    .as("the schema must refuse a NEW tip carrying a %s", column)
                    .isInstanceOf(SQLException.class);
        }

        assertThat(tipStateOf(tipId)).isEqualTo("NEW");
    }

    @Test
    @DisplayName("ck_tip_state: a dismissed tip without a dismissed time is refused by the database")
    void dismissingWithoutATimestampIsRefusedByTheSchema() throws Exception {
        String token = loginNewStudent();
        Long tipId = aGeneratedTip(token);

        assertThatThrownBy(() -> runInDatabase(
                "UPDATE user_tips SET state = 'DISMISSED' WHERE id = ?", tipId))
                .as("the schema must refuse a dismissed tip that carries no dismissed_at")
                .isInstanceOf(SQLException.class);

        assertThat(tipStateOf(tipId)).isEqualTo("NEW");
    }

    @Test
    @DisplayName("UC-18: the write the module performs is the one the schema accepts")
    void theStateChangeTheApiPerformsSatisfiesTheConstraint() throws Exception {
        String token = loginNewStudent();
        Long tipId = aGeneratedTip(token);

        assertThat(changeTipState(token, tipId, "PINNED").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tipStateOf(tipId)).isEqualTo("PINNED");
        assertThat(tipColumnOf(tipId, "pinned_at")).isNotNull();
        assertThat(tipColumnOf(tipId, "dismissed_at")).isNull();

        assertThat(changeTipState(token, tipId, "DISMISSED").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tipStateOf(tipId)).isEqualTo("DISMISSED");
        assertThat(tipColumnOf(tipId, "dismissed_at")).isNotNull();
        assertThat(tipColumnOf(tipId, "pinned_at")).isNull();
    }

    @Test
    @DisplayName("UC-18: a state change writes only the state columns, leaving the generated text alone")
    void aStateChangeDoesNotRewriteTheGeneratedColumns() throws Exception {
        String token = loginNewStudent();
        Long userId = userIdOf(token);
        Long categoryId = defaultCategoryId(TRANSPORT);
        LocalDate month = thisMonth();
        spendInCategoryWithoutBudget(token, categoryId, "40.00", month);
        Long tipId = body(generateTipsViaApi(token)).get("tips").get(0).get("id").asLong();

        Map<String, String> before = Map.of(
                "title", tipColumnOf(tipId, "title"),
                "body", tipColumnOf(tipId, "body"),
                "potential_saving", tipColumnOf(tipId, "potential_saving"),
                "rank_score", tipColumnOf(tipId, "rank_score"));

        changeTipState(token, tipId, "DISMISSED");

        for (Map.Entry<String, String> column : before.entrySet()) {
            assertThat(tipColumnOf(tipId, column.getKey()))
                    .as("%s must be left as the generator wrote it", column.getKey())
                    .isEqualTo(column.getValue());
        }
        assertThat(countOf("SELECT COUNT(*) FROM user_tips WHERE user_id = ?", userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("UC-18: a tip's month is stored as the first of the month and named as yyyy-MM")
    void aTipsMonthIsStoredAsTheFirstOfItsMonth() throws Exception {
        String token = loginNewStudent();
        Long categoryId = defaultCategoryId(TRANSPORT);
        spendInCategoryWithoutBudget(token, categoryId, "40.00", thisMonth());
        Long tipId = body(generateTipsViaApi(token)).get("tips").get(0).get("id").asLong();

        assertThat(tipColumnOf(tipId, "period_month")).isEqualTo(thisMonth().toString());
        assertThat(currentTips(token).get("periodMonth").asText()).isEqualTo(asMonth(thisMonth()));
    }

    private Long aGeneratedTip(String token) throws Exception {
        Long categoryId = defaultCategoryId(TRANSPORT);
        spendInCategoryWithoutBudget(token, categoryId, "40.00", thisMonth());
        ResponseEntity<String> generated = generateTipsViaApi(token);
        assertThat(generated.getStatusCode()).isEqualTo(HttpStatus.OK);
        return body(generated).get("tips").get(0).get("id").asLong();
    }
}
