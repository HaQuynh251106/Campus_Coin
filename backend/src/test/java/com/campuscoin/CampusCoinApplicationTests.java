package com.campuscoin;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import com.campuscoin.support.AbstractMySqlIntegrationTest;

class CampusCoinApplicationTests extends AbstractMySqlIntegrationTest {

    @Test
    void contextLoadsAgainstTheProjectSchema() throws Exception {
        try (Connection connection = openDatabaseConnection();
             Statement statement = connection.createStatement()) {

            try (ResultSet tables = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'campuscoin' AND table_type = 'BASE TABLE'")) {
                assertThat(tables.next()).isTrue();

                assertThat(tables.getInt(1)).isEqualTo(23);
            }

            try (ResultSet procedures = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.routines "
                            + "WHERE routine_schema = 'campuscoin' AND routine_type = 'PROCEDURE'")) {
                assertThat(procedures.next()).isTrue();

                assertThat(procedures.getInt(1)).isEqualTo(25);
            }

            try (ResultSet resetProcedure = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.routines "
                            + "WHERE routine_schema = 'campuscoin' "
                            + "AND routine_name IN ('sp_create_password_reset_token', "
                            + "'sp_verify_password_reset_token', 'sp_complete_password_reset')")) {
                assertThat(resetProcedure.next()).isTrue();
                assertThat(resetProcedure.getInt(1)).isEqualTo(3);
            }

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
