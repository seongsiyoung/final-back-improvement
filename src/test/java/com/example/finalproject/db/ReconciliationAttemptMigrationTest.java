package com.example.finalproject.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.testsupport.IntegrationTestSupport;
import java.nio.file.Path;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationAttemptMigrationTest extends IntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migration_backfillsExistingRowsAndAllowsNullLastReconciledAt() {
        String schema = "reconciliation_migration_" + System.nanoTime();
        jdbcTemplate.execute("create schema " + schema);
        try {
            jdbcTemplate.execute("create table " + schema + ".payments (id bigint primary key)");
            jdbcTemplate.execute("create table " + schema + ".payment_refunds (id bigint primary key)");
            jdbcTemplate.execute("create table " + schema + ".subscription_payments (id bigint primary key)");
            jdbcTemplate.execute("insert into " + schema + ".payments values (1)");
            jdbcTemplate.execute("insert into " + schema + ".payment_refunds values (1)");
            jdbcTemplate.execute("insert into " + schema + ".subscription_payments values (1)");

            jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("set search_path to " + schema);
                    try {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(
                                Path.of("db/08-reconciliation-attempts.sql"))));
                    } finally {
                        statement.execute("set search_path to public");
                    }
                }
                return null;
            });

            assertThat(jdbcTemplate.queryForObject(
                    "select reconcile_attempts from " + schema + ".payments where id = 1", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select last_reconciled_at from " + schema + ".payments where id = 1", Object.class)).isNull();
            assertThat(jdbcTemplate.queryForObject(
                    "select reconcile_attempts from " + schema + ".payment_refunds where id = 1", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select reconcile_attempts from " + schema + ".subscription_payments where id = 1", Integer.class)).isZero();
        } finally {
            jdbcTemplate.execute("drop schema " + schema + " cascade");
        }
    }
}
