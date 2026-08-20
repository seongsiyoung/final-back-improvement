package com.example.finalproject.testsupport;

import java.sql.Connection;
import java.sql.Statement;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    protected static final PostgreSQLContainer<?> POSTGRES;
    protected static final GenericContainer<?> REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>(
                DockerImageName.parse("postgis/postgis:16-3.4")
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("harness")
                .withUsername("harness")
                .withPassword("harness");
        POSTGRES.start();
        ensurePostgisExtension();

        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        REDIS.start();
    }

    private static void ensurePostgisExtension() {
        try (Connection conn = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        } catch (Exception e) {
            throw new IllegalStateException("PostGIS 확장 생성 실패", e);
        }
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
