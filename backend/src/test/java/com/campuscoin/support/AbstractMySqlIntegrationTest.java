package com.campuscoin.support;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractMySqlIntegrationTest {

    private static final String DATABASE_NAME = "campuscoin";
    private static final String SQL_SCRIPT = "campuscoin_full.sql";
    private static final String INIT_SCRIPT_PATH = "/docker-entrypoint-initdb.d/01-campuscoin.sql";

    protected static final String SEEDED_STUDENT_PASSWORD = "Student@123";

    protected static final String SEEDED_ADMIN_PASSWORD = "Admin@123";

    protected static final String SEEDED_STUDENT_EMAIL = "an.nguyen@student.campuscoin.edu";
    protected static final String SEEDED_ADMIN_EMAIL = "admin@campuscoin.edu";

    protected static final String TEST_ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    protected static String decryptField(String stored) {
        return new com.campuscoin.common.crypto.EncryptionService(
                new com.campuscoin.common.crypto.EncryptionProperties(TEST_ENCRYPTION_KEY, 1))
                .decrypt(stored);
    }

    @SuppressWarnings("resource")
    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName(DATABASE_NAME)
                    .withUsername("root")
                    .withPassword("test-root-password")
                    .withCommand(

                            "--default-time-zone=+07:00",
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_0900_ai_ci",
                            "--event-scheduler=ON",

                            "--log-bin-trust-function-creators=1")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(sqlScriptPath()),
                            INIT_SCRIPT_PATH)
                    .waitingFor(Wait.forLogMessage(".*ready for connections.*", 2))
                    .withStartupTimeout(Duration.ofMinutes(3));

    static {
        pinDockerApiVersion();
        MYSQL.start();
    }

    private static void pinDockerApiVersion() {
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.44");
        }
    }

    private static Path moduleRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        return workingDirectory.endsWith("backend") ? workingDirectory : workingDirectory.resolve("backend");
    }

    private static String sqlScriptPath() {
        Path script = moduleRoot().getParent().resolve("db").resolve("merged").resolve(SQL_SCRIPT);
        if (!script.toFile().exists()) {
            throw new IllegalStateException(
                    "The database script was not found at " + script + ". The integration tests "
                            + "load the project's own schema, so it must be present.");
        }
        return script.toString();
    }

    @DynamicPropertySource
    static void configureApplication(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET time_zone = '+07:00'");
        registry.add("campuscoin.security.jwt.secret",
                () -> "test-only-signing-key-of-at-least-thirty-two-bytes");

        registry.add("campuscoin.encryption.key", () -> TEST_ENCRYPTION_KEY);

        registry.add("campuscoin.security.password-reset.sink-enabled", () -> "true");
        registry.add("campuscoin.security.password-reset.sink-file", RESET_SINK::toString);

        registry.add("campuscoin.recurring.scheduler.enabled", () -> "false");

        registry.add("campuscoin.tips.scheduler.enabled", () -> "false");
    }

    protected static final Path RESET_SINK = Path.of(System.getProperty("java.io.tmpdir"),
            "campus-coin-tests", "password-reset-sink.log");

    protected static String jdbcUrl() {
        return MYSQL.getJdbcUrl();
    }

    protected static String databaseUser() {
        return MYSQL.getUsername();
    }

    protected static String databasePassword() {
        return MYSQL.getPassword();
    }

    protected static java.sql.Connection openDatabaseConnection() throws java.sql.SQLException {
        return java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword());
    }
}
