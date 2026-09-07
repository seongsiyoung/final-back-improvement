package com.example.finalproject.testsupport;

import java.sql.Connection;
import java.sql.Statement;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    /**
     * 모든 통합 테스트가 공유하는 Toss 대역. 클래스마다 따로 띄우면 그 선언이 컨텍스트 캐시 키를
     * 쪼개 같은 설정인데도 컨텍스트가 열 벌 넘게 만들어진다.
     */
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

    /**
     * 컨텍스트를 공유하므로 {@code CircuitBreakerRegistry} 도 공유된다. PG 실패를 만드는 테스트가
     * 회로를 열어두면 뒤따르는 테스트의 호출이 {@code NOT_SENT} 로 떨어져 엉뚱하게 실패한다.
     * 테스트마다 회로를 닫힌 상태로 되돌린다.
     */
    @BeforeEach
    void resetCircuitBreakers() {
        if (circuitBreakerFactoryForIsolation instanceof Resilience4JCircuitBreakerFactory factory) {
            factory.getCircuitBreakerRegistry().getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        }
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
