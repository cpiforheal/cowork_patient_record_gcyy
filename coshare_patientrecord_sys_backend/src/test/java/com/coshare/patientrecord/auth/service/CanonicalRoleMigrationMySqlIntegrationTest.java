package com.coshare.patientrecord.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalRoleMigrationMySqlIntegrationTest {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("auth_migration_test")
        .withUsername("clinic_test")
        .withPassword("clinic_test_password");

    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void migrateLegacyAccounts() {
        MYSQL.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("15").load().migrate();
        jdbcTemplate.update(
            """
            INSERT INTO clinic_accounts (id, username, role, status, raw_json) VALUES
              ('legacy-nurse', 'yuanzhang', 'nursing', '启用',
               JSON_OBJECT('id','legacy-nurse','username','yuanzhang','role','nursing','status','启用')),
              ('legacy-duplicate', 'yuanzhang', 'doctor', '停用',
               JSON_OBJECT('id','legacy-duplicate','username','yuanzhang','role','doctor','status','停用'))
            """
        );

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    @Test
    void migrationConvergesAliasesDuplicatesRoleProjectionAndPurgeRunSchema() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT role FROM clinic_accounts WHERE id = 'legacy-nurse'", String.class
        )).isEqualTo("nurse");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT username FROM clinic_accounts WHERE id = 'legacy-duplicate'", String.class
        )).startsWith("yuanzhang-duplicate-");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM clinic_roles", Integer.class)).isEqualTo(15);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'clinic_data_purge_runs'",
            Integer.class
        )).isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO clinic_accounts (id, username, role, status, raw_json) VALUES ('duplicate-check', 'yuanzhang', 'doctor', '启用', JSON_OBJECT())"
        )).isInstanceOf(DuplicateKeyException.class);
    }
}
