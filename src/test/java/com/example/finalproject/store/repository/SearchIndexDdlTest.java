package com.example.finalproject.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.testsupport.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class SearchIndexDdlTest extends IntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void searchIndexDdl_createsTrigramExtensionAndSearchIndexes() throws Exception {
        String ddl = Files.readString(Path.of("db/07-search-indexes.sql"), StandardCharsets.UTF_8);

        executeDdl();

        assertThat(jdbcTemplate.queryForObject(
                "select extname from pg_extension where extname = 'pg_trgm'", String.class))
                .isEqualTo("pg_trgm");
        assertThat(indexDefinition("idx_stores_location_gist"))
                .contains("USING gist", "location");
        assertThat(indexDefinition("idx_products_lower_name_trgm_gin"))
                .contains("USING gin", "lower(", "product_name", "gin_trgm_ops");
    }

    @Test
    void searchIndexDdl_buildsBothIndexesConcurrently() throws Exception {
        String ddl = Files.readString(Path.of("db/07-search-indexes.sql"), StandardCharsets.UTF_8);

        assertThat(ddl)
                .contains("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stores_location_gist")
                .contains("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_products_lower_name_trgm_gin");
    }

    private String indexDefinition(String indexName) {
        return jdbcTemplate.queryForObject(
                "select indexdef from pg_indexes where schemaname = 'public' and indexname = ?",
                String.class,
                indexName);
    }

    private void executeDdl() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new FileSystemResource("db/07-search-indexes.sql"), StandardCharsets.UTF_8));
        }
    }
}
