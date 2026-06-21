package com.example.finalproject.payment.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.auth.dto.request.LoginRequest;
import com.example.finalproject.auth.dto.response.LoginResponse;
import com.example.finalproject.global.response.ApiResponse;
import com.example.finalproject.payment.dto.request.PostPaymentConfirmRequest;
import com.example.finalproject.payment.dto.request.PostPaymentPrepareRequest;
import com.example.finalproject.payment.dto.response.PostPaymentPrepareResponse;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import com.example.finalproject.testsupport.TossStub;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * read-timeout 60초(prod와 동일) 조건에서, 서킷브레이커가 Toss 장애로 인한 톰캣 워커 점유 확산을
 * 실제로 억제하는지 검증하는 공통 부하/계측 로직. 서킷브레이커 적용 여부만 다른 두 하위 클래스
 * ({@link TossCircuitBreakerWorkerOccupancyTest}, {@link TossCircuitBreakerWorkerOccupancyBypassedTest})가
 * 이 클래스를 상속해 같은 요청 패턴으로 비교 측정한다.
 *
 * <p>수동 실행 전용({@code @Tag("manual")}는 각 하위 클래스에 붙어 있음) — 최소 2~3분이 걸리는
 * 장시간 테스트라 일반 {@code test} 태스크에서는 제외되고 {@code manualTest}에서만 실행된다.
 */
abstract class AbstractTossCircuitBreakerWorkerOccupancyTest extends IntegrationTestSupport {

    static final int TOMCAT_MAX_THREADS = 8;
    private static final int CONFIRM_ATTEMPT_POOL_SIZE = 10;
    // TimeLimiter outer bound(read-timeout 60000ms + 2000ms 버퍼 = 62000ms)보다 확실히 길게 잡아,
    // 실제로 시간을 끊는 주체가 항상 클라이언트 쪽(Feign read-timeout/TimeLimiter)이 되도록 한다 —
    // WireMock 스텁 자체의 지연이 먼저 끝나버리면 이 테스트가 검증하려는 상황이 재현되지 않는다.
    private static final long WIREMOCK_DELAY_MS = 90_000;
    private static final long TOSS_READ_TIMEOUT_MS = 60_000;
    // 60초(첫 실패 확인) + 10초(OPEN 대기, TossResilienceConfig 기본값) + 60초(HALF_OPEN probe) +
    // 여유(기동/폴링 오차)를 감안해, OPEN -> HALF_OPEN -> (다시 실패 시) OPEN 사이클을 최소 한 번
    // 관측할 수 있는 길이로 잡는다.
    private static final long TEST_DURATION_MS = 150_000;
    private static final long CATEGORIES_INTERVAL_MS = 150;
    private static final long POLL_INTERVAL_MS = 250;
    // fail-fast(서킷 OPEN/HALF_OPEN 차단) 응답 뒤 다음 confirm을 재제출하기 전에 두는 페이싱 —
    // 이게 없으면 슬롯 10개가 초당 수백~수천 건씩 재제출을 반복해 톰캣 busy 지표가
    // "실제 워커 점유 시간"이 아니라 "요청 폭주"로 왜곡된다.
    private static final long RESUBMIT_PACING_MS = 200;

    @RegisterExtension
    static TossStub toss = new TossStub();

    @DynamicPropertySource
    static void tossProps(DynamicPropertyRegistry registry) {
        registry.add("toss.payments.base-url", toss::baseUrl);
        // 이 테스트에만 적용 — application-test.yml(전역)을 건드리면 다른 통합 테스트에까지
        // 영향 범위가 넓어진다(ThreadExhaustionTest와 동일한 이유).
        registry.add("server.tomcat.threads.max", () -> TOMCAT_MAX_THREADS);
        registry.add("server.tomcat.threads.min-spare", () -> TOMCAT_MAX_THREADS);
        // tomcat.threads.busy 메트릭은 Tomcat MBean 등록이 켜져 있어야 노출된다(기본 꺼짐).
        registry.add("server.tomcat.mbeanregistry.enabled", () -> true);
        // prod와 동일한 조건을 재현한다 — 이 값이 이번 테스트의 핵심 전제다.
        registry.add("spring.cloud.openfeign.client.config.tossPaymentsClient.read-timeout",
                () -> TOSS_READ_TIMEOUT_MS);
        registry.add("management.endpoints.web.exposure.include", () -> "health,metrics");
        // application-test.yml에 TossCircuitBreakerTest 전용으로 500ms(빠른 half-open 재현용)가
        // 이미 박혀 있다 — 여기서 명시적으로 override 안 하면 그 값을 그대로 물려받아
        // waitDurationInOpenState=10초라는 이번 테스트의 설계 전제가 깨진다.
        registry.add("toss.circuit-breaker.wait-duration-in-open-state-ms", () -> 10_000);
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private LoadTestDataSeeder seeder;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    protected CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    private String accessToken;
    private Long productId;

    @BeforeEach
    void setUpWorkerOccupancyTest() {
        String email = "cb-occupancy-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(email, "password1234!");
        seeder.seedStoreWithProducts(1, 1000);

        LoginRequest loginRequest = new LoginRequest();
        ReflectionTestUtils.setField(loginRequest, "email", email);
        ReflectionTestUtils.setField(loginRequest, "password", "password1234!");
        ResponseEntity<ApiResponse<LoginResponse>> loginResponse = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(loginRequest),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        accessToken = loginResponse.getBody().getData().getAccessToken();

        Store store = productRepository.findAll().stream().map(Product::getStore).findFirst().orElseThrow();
        productId = productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().get(0).getId();

        toss.server.stubFor(post(urlPathMatching("/v1/payments/confirm"))
                .willReturn(aResponse()
                        .withFixedDelay((int) WIREMOCK_DELAY_MS)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"paymentKey":"test-payment-key","orderId":"test-order-id",
                                 "totalAmount":10000,"status":"DONE",
                                 "approvedAt":"2026-08-17T00:00:00+09:00",
                                 "receipt":{"url":"https://dashboard.tosspayments.com/receipt/test"}}
                                """)));
    }

    // === 계측 레코드 ===

    /** confirm 시도 1건의 결과. durationMs가 짧으면(<500ms) 서킷 OPEN에 의한 fail-fast로 본다. */
    record ConfirmAttemptResult(long startElapsedMs, long durationMs, boolean failFast, String outcome) {}

    /** 폴링 시점의 시스템 상태 스냅샷(톰캣 워커 점유, WireMock 도달 건수, JVM 스레드 수). */
    record OccupancySample(long elapsedMs, double tomcatBusy, long wiremockConfirmCount, int jvmThreadsLive) {}

    /** 무관 API(categories) 요청 1건의 응답 시간. */
    record CategoriesSample(long elapsedMs, long latencyMs) {}

    /** CB 상태 전이 1건(있는 경우에만 기록됨 — 우회 조건에서는 비어 있음). */
    record StateTransitionEvent(long elapsedMs, CircuitBreaker.State from, CircuitBreaker.State to) {}

    /**
     * 부하를 걸고 계측한 뒤 콘솔에 CLOSED-초기/OPEN/HALF_OPEN 구간별 정리표를 출력한다.
     * 서킷브레이커 적용 여부와 무관하게 완전히 같은 요청 패턴으로 실행된다 — 두 조건을
     * 갈라야 하는 유일한 지점은 어떤 {@link CircuitBreakerFactory} 빈이 주입됐는지뿐이다.
     */
    protected void runLoadAndPrintReport(String label) throws Exception {
        long testStart = System.currentTimeMillis();
        List<StateTransitionEvent> transitions = new CopyOnWriteArrayList<>();

        if (circuitBreakerFactory instanceof Resilience4JCircuitBreakerFactory real) {
            // .run()을 최소 한 번 실행해야 registry에 우리 커스텀 설정으로 인스턴스가 생성된다
            // (Task 7/8 학습 문서 참고) — 아래 confirm 부하 생성기가 곧바로 이 조건을 만족시킨다.
            // 리스너는 그 실제 인스턴스에 걸어야 하므로, 첫 confirm 시도 후 지연 등록한다.
            registerStateTransitionListenerAfterFirstRun(real, transitions, testStart);
        }

        List<ConfirmAttemptResult> confirmResults = new CopyOnWriteArrayList<>();
        List<OccupancySample> occupancySamples = new CopyOnWriteArrayList<>();
        List<CategoriesSample> categoriesSamples = new CopyOnWriteArrayList<>();
        AtomicBoolean keepRunning = new AtomicBoolean(true);

        ExecutorService confirmPool = Executors.newFixedThreadPool(CONFIRM_ATTEMPT_POOL_SIZE);
        ExecutorService categoriesPool = Executors.newFixedThreadPool(2);
        ExecutorService pollerPool = Executors.newSingleThreadExecutor();

        try {
            // 결제 장애 요청 생성기 — 하나가 끝나는 즉시(성공/실패/fail-fast 무관) 다음 시도를
            // 계속 submit해, 풀 크기(10)를 넘지 않는 선에서 지속적으로 confirm을 시도한다.
            for (int i = 0; i < CONFIRM_ATTEMPT_POOL_SIZE; i++) {
                submitNextConfirmAttempt(confirmPool, keepRunning, confirmResults, testStart);
            }

            // 무관 API(categories) 생성기 — 결제 요청 대기와 완전히 독립적으로 계속 발사한다.
            for (int i = 0; i < 2; i++) {
                categoriesPool.submit(() -> {
                    while (keepRunning.get()) {
                        long start = System.currentTimeMillis();
                        try {
                            restTemplate.getForEntity("/api/stores/categories", String.class);
                        } catch (Exception ignored) {
                            // 관찰 대상은 응답 시간이지 성공 여부가 아니다.
                        }
                        long latency = System.currentTimeMillis() - start;
                        categoriesSamples.add(new CategoriesSample(start - testStart, latency));
                        sleepQuietly(CATEGORIES_INTERVAL_MS);
                    }
                });
            }

            // 톰캣 워커 점유/WireMock 도달 건수 폴러
            pollerPool.submit(() -> {
                while (keepRunning.get()) {
                    long elapsed = System.currentTimeMillis() - testStart;
                    double busy = readGaugeOrNan("tomcat.threads.busy");
                    long wiremockCount =
                            toss.server.findAll(postRequestedFor(urlPathMatching("/v1/payments/confirm"))).size();
                    int jvmThreads = Thread.activeCount();
                    occupancySamples.add(new OccupancySample(elapsed, busy, wiremockCount, jvmThreads));
                    sleepQuietly(POLL_INTERVAL_MS);
                }
            });

            Thread.sleep(TEST_DURATION_MS);
        } finally {
            keepRunning.set(false);
            confirmPool.shutdownNow();
            categoriesPool.shutdownNow();
            pollerPool.shutdownNow();
            confirmPool.awaitTermination(65, TimeUnit.SECONDS);
            categoriesPool.awaitTermination(5, TimeUnit.SECONDS);
            pollerPool.awaitTermination(5, TimeUnit.SECONDS);
        }

        printReport(label, testStart, transitions, confirmResults, occupancySamples, categoriesSamples);

        // negative check: 부하 자체가 실제로 톰캣 워커를 압박했는지(재현 성립 여부) 조건 무관하게 확인.
        // 이게 없으면 "서킷브레이커 있는 조건이 통과했다"는 것만으로는 재현 자체가 안 됐을 가능성을
        // 배제 못 한다.
        double maxBusyObserved = occupancySamples.stream()
                .mapToDouble(OccupancySample::tomcatBusy)
                .filter(v -> !Double.isNaN(v))
                .max().orElse(0);
        assertThat(maxBusyObserved)
                .as("[%s] 톰캣 워커가 실제로 압박받았어야 한다(재현 성립 여부 negative check)", label)
                .isGreaterThanOrEqualTo(TOMCAT_MAX_THREADS - 1);
    }

    private void registerStateTransitionListenerAfterFirstRun(
            Resilience4JCircuitBreakerFactory factory, List<StateTransitionEvent> sink, long testStart) {
        // 주의: registry.circuitBreaker(id)(1개 인자)는 아직 등록된 인스턴스가 없으면 그 자리에서
        // resilience4j 기본 설정(minimumNumberOfCalls=100 등 우리 커스텀 값과 다름)으로 즉시 만들어
        // 버린다. confirm 부하 생성기의 진짜 .run() 호출(id+config+tags 3개 인자, 우리 커스텀 설정)보다
        // 이 폴링이 먼저 실행되면, 레지스트리에 잘못된 기본 설정 인스턴스가 선점되고 이후 모든 호출이
        // 그 인스턴스를 계속 재사용해버린다 — 결과적으로 실패가 아무리 쌓여도 100건에 못 미쳐 영원히
        // OPEN되지 않는다(실제로 이 버그로 첫 실행에서 서킷이 전혀 안 열렸다). registry.find(id)는
        // 없으면 Optional.empty()만 반환하고 아무것도 생성하지 않으므로, confirm 쪽이 먼저 만들어둔
        // 진짜 인스턴스가 나타날 때까지 안전하게 기다릴 수 있다.
        Executors.newSingleThreadExecutor().submit(() -> {
            for (int i = 0; i < 200; i++) {
                Optional<CircuitBreaker> existing = factory.getCircuitBreakerRegistry().find("toss-payment");
                if (existing.isPresent()) {
                    existing.get().getEventPublisher().onStateTransition(event -> sink.add(new StateTransitionEvent(
                            System.currentTimeMillis() - testStart,
                            event.getStateTransition().getFromState(),
                            event.getStateTransition().getToState())));
                    return;
                }
                sleepQuietly(50);
            }
        });
    }

    private void submitNextConfirmAttempt(
            ExecutorService pool, AtomicBoolean keepRunning,
            List<ConfirmAttemptResult> results, long testStart) {
        if (!keepRunning.get() || pool.isShutdown()) {
            return;
        }
        pool.submit(() -> {
            if (!keepRunning.get()) {
                return;
            }
            long start = System.currentTimeMillis();
            String outcome;
            try {
                Long paymentId = prepareNewPayment();
                callConfirm(paymentId);
                outcome = "COMPLETED";
            } catch (Exception e) {
                outcome = e.getClass().getSimpleName();
            }
            long duration = System.currentTimeMillis() - start;
            boolean failFast = duration < 500;
            results.add(new ConfirmAttemptResult(
                    start - testStart, duration, failFast, outcome));
            if (failFast) {
                // 서킷이 OPEN/HALF_OPEN이면 매 시도가 몇 ms 만에 fail-fast로 끝나서, 대기 없이
                // 바로바로 재제출하면 슬롯 10개가 초당 수백~수천 건씩 쏴대는 플러딩이 된다 —
                // "워커가 오래 붙잡혀서 바쁘다"가 아니라 "요청이 너무 많이 몰려서 바쁘다"가 돼버려
                // 톰캣 busy 지표가 서킷 효과를 보여주지 못하게 왜곡된다. fail-fast일 때만 짧게
                // 페이싱을 줘서 실제 운영에서 재시도할 법한 속도에 가깝게 맞춘다. CLOSED 구간(각
                // 시도가 최대 60초 걸림)은 이 지연이 붙어도 사실상 영향이 없다.
                sleepQuietly(RESUBMIT_PACING_MS);
            }
            submitNextConfirmAttempt(pool, keepRunning, results, testStart);
        });
    }

    private Long prepareNewPayment() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        PostPaymentPrepareRequest prepareRequest = new PostPaymentPrepareRequest();
        ReflectionTestUtils.setField(prepareRequest, "productQuantities", Map.of(productId, 1));
        ReflectionTestUtils.setField(prepareRequest, "paymentMethod", PaymentMethodType.CARD);
        ReflectionTestUtils.setField(prepareRequest, "deliveryAddress", "서울시 강남구 테헤란로 123");
        ResponseEntity<ApiResponse<PostPaymentPrepareResponse>> prepareResponse = restTemplate.exchange(
                "/api/payments/prepare", HttpMethod.POST, new HttpEntity<>(prepareRequest, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        return prepareResponse.getBody().getData().getPaymentId();
    }

    private void callConfirm(Long paymentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        PostPaymentConfirmRequest confirmRequest = new PostPaymentConfirmRequest();
        ReflectionTestUtils.setField(confirmRequest, "paymentId", paymentId);
        ReflectionTestUtils.setField(confirmRequest, "paymentKey",
                "test-payment-key-" + paymentId + "-" + System.nanoTime());
        restTemplate.exchange("/api/payments/confirm", HttpMethod.POST,
                new HttpEntity<>(confirmRequest, headers), String.class);
    }

    private double readGaugeOrNan(String metricName) {
        try {
            // /actuator/**도 test 프로파일 SecurityConfig 기준 anyRequest().authenticated()라
            // 인증 헤더 없이는 401만 받는다(그래서 처음엔 계속 NaN이었다) — 로그인해둔
            // accessToken을 그대로 실어 보낸다.
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            ResponseEntity<Map> response = restTemplate.exchange(
                    "/actuator/metrics/" + metricName, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            List<Map<String, Object>> measurements = (List<Map<String, Object>>) response.getBody().get("measurements");
            return ((Number) measurements.get(0).get("value")).doubleValue();
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void printReport(
            String label, long testStart, List<StateTransitionEvent> transitions,
            List<ConfirmAttemptResult> confirmResults, List<OccupancySample> occupancySamples,
            List<CategoriesSample> categoriesSamples) {

        System.out.println("\n===== [" + label + "] Toss 장애 시 워커 점유 측정 결과 =====");
        System.out.println("총 소요: " + (System.currentTimeMillis() - testStart) + "ms");

        System.out.println("-- CB 상태 전이 --");
        if (transitions.isEmpty()) {
            System.out.println("  (없음 — 서킷브레이커 우회 조건이거나, 관측 시간 내 전이 없음)");
        } else {
            transitions.forEach(t -> System.out.printf(
                    "  t=%6dms  %s -> %s%n", t.elapsedMs(), t.from(), t.to()));
        }

        // 구간 경계: 전이 이벤트가 있으면 그걸 기준으로, 없으면 전체를 단일 구간으로 취급한다.
        List<Long> boundaries = new ArrayList<>();
        boundaries.add(0L);
        transitions.forEach(t -> boundaries.add(t.elapsedMs()));
        boundaries.add(TEST_DURATION_MS);

        for (int i = 0; i < boundaries.size() - 1; i++) {
            long from = boundaries.get(i);
            long to = boundaries.get(i + 1);
            String phaseLabel = i == 0 ? "구간 " + (i + 1) + " (초기, t<" + to + "ms)"
                    : "구간 " + (i + 1) + " (t=" + from + "~" + to + "ms)";
            System.out.println("-- " + phaseLabel + " --");

            List<OccupancySample> phaseOccupancy = occupancySamples.stream()
                    .filter(s -> s.elapsedMs() >= from && s.elapsedMs() < to).toList();
            double maxBusy = phaseOccupancy.stream().mapToDouble(OccupancySample::tomcatBusy)
                    .filter(v -> !Double.isNaN(v)).max().orElse(Double.NaN);
            double avgBusy = phaseOccupancy.stream().mapToDouble(OccupancySample::tomcatBusy)
                    .filter(v -> !Double.isNaN(v)).average().orElse(Double.NaN);
            long wiremockBefore = phaseOccupancy.isEmpty() ? 0 : phaseOccupancy.get(0).wiremockConfirmCount();
            long wiremockAfter = phaseOccupancy.isEmpty() ? 0
                    : phaseOccupancy.get(phaseOccupancy.size() - 1).wiremockConfirmCount();
            System.out.printf("  톰캣 busy: max=%.1f avg=%.1f%n", maxBusy, avgBusy);
            System.out.printf("  WireMock confirm 도달 건수 증가: %d (%d -> %d)%n",
                    wiremockAfter - wiremockBefore, wiremockBefore, wiremockAfter);

            List<CategoriesSample> phaseCategories = categoriesSamples.stream()
                    .filter(s -> s.elapsedMs() >= from && s.elapsedMs() < to).toList();
            if (!phaseCategories.isEmpty()) {
                List<Long> latencies = phaseCategories.stream()
                        .map(CategoriesSample::latencyMs).sorted().toList();
                long p50 = latencies.get(latencies.size() / 2);
                long p95 = latencies.get((int) (latencies.size() * 0.95));
                long max = latencies.get(latencies.size() - 1);
                System.out.printf("  categories 응답시간(n=%d): p50=%dms p95=%dms max=%dms%n",
                        latencies.size(), p50, p95, max);
            } else {
                System.out.println("  categories 샘플 없음");
            }

            List<ConfirmAttemptResult> phaseConfirms = confirmResults.stream()
                    .filter(r -> r.startElapsedMs() >= from && r.startElapsedMs() < to).toList();
            long failFastCount = phaseConfirms.stream().filter(ConfirmAttemptResult::failFast).count();
            long slowCount = phaseConfirms.size() - failFastCount;
            System.out.printf("  confirm 시도: 총 %d건 (fail-fast %d건, 완료까지 대기 %d건)%n",
                    phaseConfirms.size(), failFastCount, slowCount);
        }
        System.out.println("=====================================================\n");
    }
}
