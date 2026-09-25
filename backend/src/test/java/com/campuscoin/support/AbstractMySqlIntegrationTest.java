package com.campuscoin.support;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Base class for tests that exercise the real API against a real MySQL 8.
 *
 * <p>Why a real database rather than an in-memory one: this module's behaviour depends on stored
 * procedures, a unique key and a deterministic clock. An H2 or embedded substitute would have to
 * fake all three, and the tests would then prove nothing about the schema that actually runs. It
 * is also the only way to prove the JPA entities match the real schema - the tests start the
 * application with {@code ddl-auto=validate}, so a mismatched column fails the context.
 *
 * <p>The project's own {@code db/merged/campuscoin_full.sql} is mounted at
 * {@code /docker-entrypoint-initdb.d/01-campuscoin.sql} and executed by the container's own
 * entrypoint. That detail matters: the file uses {@code DELIMITER $$} for its procedures, which
 * a JDBC script runner cannot parse, whereas the MySQL client the entrypoint uses handles it
 * natively. Loading the file through the entrypoint also means the test database is built exactly
 * the way {@code docker-compose.yml} builds the development one.
 *
 * <p>The container runs with the same server flags as the compose file, so the time zone and
 * character set under test match production. The connection is made as root because the script
 * creates the database and its procedures, and the assertions need to read tables that the
 * application account cannot necessarily see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractMySqlIntegrationTest {

    private static final String DATABASE_NAME = "campuscoin";
    private static final String SQL_SCRIPT = "campuscoin_full.sql";
    private static final String INIT_SCRIPT_PATH = "/docker-entrypoint-initdb.d/01-campuscoin.sql";

    /** The hash of the seeded password {@code Student@123}, as {@code db/05_seed.sql} stores it. */
    protected static final String SEEDED_STUDENT_PASSWORD = "Student@123";

    /** The hash of the seeded password {@code Admin@123}, as {@code db/05_seed.sql} stores it. */
    protected static final String SEEDED_ADMIN_PASSWORD = "Admin@123";

    protected static final String SEEDED_STUDENT_EMAIL = "an.nguyen@student.campuscoin.edu";
    protected static final String SEEDED_ADMIN_EMAIL = "admin@campuscoin.edu";

    /**
     * One container for all subclasses. Starting a fresh MySQL per test class would multiply the
     * suite's runtime by the number of classes for no extra isolation: each test creates the rows
     * it needs and does not depend on the seed data staying untouched.
     *
     * <p>The image tag is pinned to 8.0 to match {@code docker-compose.yml}.
     */
    @SuppressWarnings("resource")
    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName(DATABASE_NAME)
                    .withUsername("root")
                    .withPassword("test-root-password")
                    .withCommand(
                            // Matches docker-compose.yml. The fixed +07:00 offset is what the
                            // views and procedures derive day, week and month boundaries from
                            // (VĐ-10); running the tests in UTC would hide boundary bugs.
                            "--default-time-zone=+07:00",
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_0900_ai_ci",
                            "--event-scheduler=ON",
                            // MySQL 8.0 enables the binary log by default, and creating a
                            // function without this flag is refused for lack of SUPER.
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

    /**
     * Pins the Docker Engine API version docker-java negotiates.
     *
     * <p>Without this, Testcontainers fails on Docker Engine 29 with a misleading
     * "Could not find a valid Docker environment", even though {@code docker ps} works: the
     * version the bundled client asks for is below 1.40, which the daemon rejects with 400.
     * It is set here rather than through {@code docker-java.properties} on the test classpath,
     * because that file is read before the property reaches the client and does not take effect.
     *
     * <p>An explicit value from the environment or the command line always wins, so a developer
     * targeting an older daemon can override it.
     */
    private static void pinDockerApiVersion() {
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.44");
        }
    }

    /** {@code backend/} - the module root, wherever the suite is launched from. */
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

    /**
     * Points the application at the container.
     *
     * <p>The JWT secret is supplied here rather than read from the environment so the suite runs
     * without any setup. It is a test-only value and is never used outside this class.
     */
    @DynamicPropertySource
    static void configureApplication(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET time_zone = '+07:00'");
        registry.add("campuscoin.security.jwt.secret",
                () -> "test-only-signing-key-of-at-least-thirty-two-bytes");
        // The reset sink is written to a temporary file the test can read, so the flow can be
        // completed end to end exactly as a developer would. It never goes to the log.
        registry.add("campuscoin.security.password-reset.sink-enabled", () -> "true");
        registry.add("campuscoin.security.password-reset.sink-file", RESET_SINK::toString);
        // The recurring scheduler is a timer (UC-09). Left on, it would fire whenever the wall
        // clock reached its cron minute and write transactions in the middle of an assertion, so
        // the same procedure is called directly through RecurringProcedureDao with explicit dates
        // instead. Turning it off here rather than in application.yml keeps the scheduled path
        // switched on everywhere the application really runs.
        registry.add("campuscoin.recurring.scheduler.enabled", () -> "false");
        // The saving-tip scheduler is a timer for the same reason (UC-18). Left on, it would
        // generate tips for every active student mid-test, and a test asserting "this month has no
        // tips" would then fail for a reason that has nothing to do with the code under test. The
        // procedure is called directly through TipGenerationDao with an explicit month instead.
        registry.add("campuscoin.tips.scheduler.enabled", () -> "false");
    }

    /** The development-style reset sink, pointed at a throwaway file for the test run. */
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

    /** Connects to the test database as root, for assertions on rows the API does not expose. */
    protected static java.sql.Connection openDatabaseConnection() throws java.sql.SQLException {
        return java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword());
    }
}
