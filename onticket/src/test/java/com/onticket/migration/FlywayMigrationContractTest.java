package com.onticket.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayMigrationContractTest {

    static final MariaDBContainer<?> MARIA_DB = new MariaDBContainer<>("mariadb:10.11.8")
            .withDatabaseName("onticket_flyway")
            .withUsername("onticket")
            .withPassword("onticket");

    @BeforeAll
    static void startDatabase() {
        MARIA_DB.start();
    }

    @AfterEach
    void dropContractSchemas() throws Exception {
        flyway(false).clean();
    }

    @Test
    void migratesAnEmptySchemaWithReservationConstraints() throws Exception {
        Flyway flyway = flyway(false);
        flyway.clean();
        MigrateResult result = flyway.migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(indexExists("seat", "uk_seat_concert_time_number")).isTrue();
        assertThat(indexExists("reservation_checkout", "uk_checkout_merchant_uid")).isTrue();
        assertThat(indexExists("reservation_payment", "uk_payment_provider_payment_id")).isTrue();
    }

    @Test
    void baselinesAnExistingV1EquivalentSchemaWithoutReapplyingV1() throws Exception {
        Flyway initialMigration = flyway(false);
        initialMigration.clean();
        initialMigration.migrate();

        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE flyway_schema_history");
        }

        MigrateResult result = flyway(true).migrate();

        assertThat(result.migrationsExecuted).isZero();
        assertThat(indexExists("seat", "uk_seat_concert_time_number")).isTrue();
        assertThat(historyType()).isEqualTo("BASELINE");
    }

    @Test
    void failsFastForANonEmptySchemaWithoutHistoryUntilBaselineIsExplicitlyOptedIn() throws Exception {
        Flyway flyway = flyway(false);
        flyway.clean();
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unknown_legacy_schema (id BIGINT NOT NULL PRIMARY KEY)");
        }

        assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty schema");
    }

    private Flyway flyway(boolean baselineOnMigrate) {
        return Flyway.configure()
                .dataSource(MARIA_DB.getJdbcUrl(), MARIA_DB.getUsername(), MARIA_DB.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion("1")
                .cleanDisabled(false)
                .load();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(MARIA_DB.getJdbcUrl(), MARIA_DB.getUsername(), MARIA_DB.getPassword());
    }

    private boolean indexExists(String table, String index) throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM information_schema.statistics
                     WHERE table_schema = '%s'
                       AND table_name = '%s'
                       AND index_name = '%s'
                     """.formatted(MARIA_DB.getDatabaseName(), table, index))) {
            resultSet.next();
            return resultSet.getInt(1) > 0;
        }
    }

    private String historyType() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT type FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

}
