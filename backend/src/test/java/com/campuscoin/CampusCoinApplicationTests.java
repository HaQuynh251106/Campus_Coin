package com.campuscoin;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import com.campuscoin.support.AbstractMySqlIntegrationTest;

/**
 * Proves the application starts against the real schema.
 *
 * <p>This is the test that makes {@code ddl-auto=validate} meaningful. The context only comes up
 * if every mapped entity matches the actual columns of {@code db/merged/campuscoin_full.sql}, so
 * a renamed column, a changed type or an enum value that does not exist in the database fails
 * here rather than at run time. It also proves the schema script loaded, since the entities
 * cannot be validated against an empty database.
 */
class CampusCoinApplicationTests extends AbstractMySqlIntegrationTest {

    @Test
    void contextLoadsAgainstTheProjectSchema() throws Exception {
        try (Connection connection = openDatabaseConnection();
             Statement statement = connection.createStatement()) {

            try (ResultSet tables = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'campuscoin' AND table_type = 'BASE TABLE'")) {
                assertThat(tables.next()).isTrue();
                // The script creates 23 tables; the exact count guards against a partial load,
                // which would still let the context start as long as the mapped tables existed.
                assertThat(tables.getInt(1)).isEqualTo(23);
            }

            try (ResultSet procedures = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.routines "
                            + "WHERE routine_schema = 'campuscoin' AND routine_type = 'PROCEDURE'")) {
                assertThat(procedures.next()).isTrue();
                assertThat(procedures.getInt(1)).isEqualTo(24);
            }

            // The three procedures this module calls must exist by name, not merely in number.
            try (ResultSet resetProcedure = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.routines "
                            + "WHERE routine_schema = 'campuscoin' "
                            + "AND routine_name IN ('sp_create_password_reset_token', "
                            + "'sp_verify_password_reset_token', 'sp_complete_password_reset')")) {
                assertThat(resetProcedure.next()).isTrue();
                assertThat(resetProcedure.getInt(1)).isEqualTo(3);
            }

            // Seed data must be present: several tests sign in as the seeded accounts. The count
            // is asserted as "at least", not "exactly", because every other test class registers
            // accounts in this same container and JUnit does not promise class order - a fixed
            // total would make this test pass or fail depending on which classes ran first.
            try (ResultSet users = statement.executeQuery(
                    "SELECT COUNT(*) FROM users WHERE email IN ('admin@campuscoin.edu', "
                            + "'an.nguyen@student.campuscoin.edu', "
                            + "'binh.tran@student.campuscoin.edu')")) {
                assertThat(users.next()).isTrue();
                assertThat(users.getInt(1)).as("the seeded accounts are present").isEqualTo(3);
            }

            try (ResultSet timeZone = statement.executeQuery("SELECT @@session.time_zone")) {
                assertThat(timeZone.next()).isTrue();
                assertThat(timeZone.getString(1)).isEqualTo("+07:00");
            }
        }
    }
}
