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

/**
 * The {@code ck_tip_state} pairing, asserted against the real schema.
 *
 * <p><b>Why this class exists.</b> This is the one module whose state change is not a stored
 * procedure: the schema provides no {@code sp_set_tip_state}, so {@code UserTip.setState} derives the
 * timestamp from the state and the service writes the pair itself. That derivation is the module's
 * own, and if it were ever reduced to "set {@code state}" - which is what a plain flag update would
 * look like - the row would violate {@code ck_tip_state} and the write would be refused by the
 * database rather than by any code here.
 *
 * <p>The tests below apply that wrong write <em>directly</em>, past the entity, so they prove the
 * constraint is live rather than merely documented - a schema copy without the CHECK would fail them.
 * The last test then shows the write the module actually performs succeeding, so the pair of results
 * pins the difference the constraint draws rather than one side of it.
 *
 * <p>A state change and its timestamp are one fact about a tip, and the schema is what says so. Code
 * that agreed with the schema only by convention would be code that could drift from it silently;
 * this class is what makes the drift fail loudly.
 */
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

        // The row is untouched, so the refused write left nothing half-applied.
        assertThat(tipStateOf(tipId)).isEqualTo("NEW");
    }

    @Test
    @DisplayName("ck_tip_state: a pinned tip carrying a dismissed time is refused by the database")
    void pinningWithAStaleDismissalTimestampIsRefused() throws Exception {
        String token = loginNewStudent();
        Long tipId = aGeneratedTip(token);

        // The exact mistake a setter taking only a state would produce after a dismiss-then-pin:
        // the new state with the old state's timestamp still in place.
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

        // Pinned: state and pinned_at together, dismissed_at cleared. The rows above are the writes
        // that would have been refused; this is the write TipService makes, and it is accepted -
        // which is what makes the constraint a guard rather than an obstacle.
        assertThat(changeTipState(token, tipId, "PINNED").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tipStateOf(tipId)).isEqualTo("PINNED");
        assertThat(tipColumnOf(tipId, "pinned_at")).isNotNull();
        assertThat(tipColumnOf(tipId, "dismissed_at")).isNull();

        // Dismissed: the pair moves to the other column, and pinned_at is cleared rather than left
        // behind - the second of the two mismatches the schema refuses.
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

        // The generator's values, as the database holds them.
        Map<String, String> before = Map.of(
                "title", tipColumnOf(tipId, "title"),
                "body", tipColumnOf(tipId, "body"),
                "potential_saving", tipColumnOf(tipId, "potential_saving"),
                "rank_score", tipColumnOf(tipId, "rank_score"));

        changeTipState(token, tipId, "DISMISSED");

        // @DynamicUpdate restricts the UPDATE to the columns the entity changed, so a pin or dismiss
        // cannot carry a stale copy of the rendered text back over a concurrent generation's row -
        // and cannot rewrite the fields the procedure computed. `rank_score` and `potential_saving`
        // have no setter at all, so their survival is the strongest form of this guarantee.
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

        // The date the views partition by is the first of the month, and the API names that same
        // value as `yyyy-MM` - one convention, not two.
        assertThat(tipColumnOf(tipId, "period_month")).isEqualTo(thisMonth().toString());
        assertThat(currentTips(token).get("periodMonth").asText()).isEqualTo(asMonth(thisMonth()));
    }

    /** Registers a student, gives them spending that produces one tip, and returns the tip's id. */
    private Long aGeneratedTip(String token) throws Exception {
        Long categoryId = defaultCategoryId(TRANSPORT);
        spendInCategoryWithoutBudget(token, categoryId, "40.00", thisMonth());
        ResponseEntity<String> generated = generateTipsViaApi(token);
        assertThat(generated.getStatusCode()).isEqualTo(HttpStatus.OK);
        return body(generated).get("tips").get(0).get("id").asLong();
    }
}
