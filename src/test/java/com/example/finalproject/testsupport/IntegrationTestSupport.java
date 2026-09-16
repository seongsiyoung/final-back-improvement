package com.example.finalproject.testsupport;

import java.sql.Connection;
import java.sql.Statement;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
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

    @RegisterExtension
    protected static final SharedTossStub toss = new SharedTossStub();

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

    @Autowired(required = false)
    private CircuitBreakerFactory<?, ?> circuitBreakerFactoryForIsolation;

    @BeforeEach
    void resetCircuitBreakers() {
        if (circuitBreakerFactoryForIsolation instanceof Resilience4JCircuitBreakerFactory factory) {
            factory.getCircuitBreakerRegistry().getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        }
    }


    /**
     * 다른 스레드가 실제로 행 잠금 대기에 들어갈 때까지 기다린다.
     *
     * <p>sleep 으로 맞추면 느린 환경에서 대기 전에 앞 트랜잭션이 커밋해 두 호출이 순차 실행된다.
     * 그러면 낡은 읽기가 아예 일어나지 않아 고치기 전 구현으로도 통과한다.
     */
    protected void awaitRowLockWaiter(JdbcTemplate jdbcTemplate) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "select count(*) from pg_locks "
                            + "where not granted and locktype in ('transactionid', 'tuple')",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("잠금 대기에 들어간 스레드가 없다");
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("toss.payments.base-url", toss::baseUrl);
    }
}
