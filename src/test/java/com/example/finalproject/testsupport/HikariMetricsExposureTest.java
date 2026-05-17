package com.example.finalproject.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.auth.dto.request.LoginRequest;
import com.example.finalproject.auth.dto.response.LoginResponse;
import com.example.finalproject.global.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class HikariMetricsExposureTest extends IntegrationTestSupport {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private LoadTestDataSeeder seeder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String email;

    @BeforeEach
    void setUp() {
        // SecurityConfig(test 프로파일에서도 활성)가 /actuator/**를 anyRequest().authenticated()로
        // 막고 있어, 실제 로그인해서 얻은 JWT가 있어야 액추에이터 엔드포인트에 접근할 수 있다.
        email = "hikari-metrics-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(email, "password1234!");
    }

    @Test
    void hikariActiveConnectionsMetricIsExposed() {
        // HikariCP 메트릭 바인더는 커넥션 풀이 최소 한 번 사용된 뒤에야 등록된다.
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);

        LoginRequest loginRequest = new LoginRequest();
        ReflectionTestUtils.setField(loginRequest, "email", email);
        ReflectionTestUtils.setField(loginRequest, "password", "password1234!");

        ResponseEntity<ApiResponse<LoginResponse>> loginResponse = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(loginRequest),
                new ParameterizedTypeReference<>() {});

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = loginResponse.getBody().getData().getAccessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/metrics/hikaricp.connections.active", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("hikaricp.connections.active");
    }
}
