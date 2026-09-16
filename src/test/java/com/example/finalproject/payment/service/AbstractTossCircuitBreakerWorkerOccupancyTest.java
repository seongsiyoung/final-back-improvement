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
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
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

/** Toss 장애 시 서킷브레이커 적용 여부에 따른 워커 점유를 측정한다. */
abstract class AbstractTossCircuitBreakerWorkerOccupancyTest extends IntegrationTestSupport {

    protected int tomcatMaxThreads() {
        return 8;
    }

    protected int confirmAttemptPoolSize() {
        return 10;
    }

    private static final long WIREMOCK_DELAY_MS = 90_000;
    private static final long TOSS_READ_TIMEOUT_MS = 60_000;
    private static final long TEST_DURATION_MS = 150_000;
    private static final long CATEGORIES_INTERVAL_MS = 150;
    private static final long POLL_INTERVAL_MS = 250;
    private static final double BASELINE_BUSY_THRESHOLD = 2.0;
    private static final long BASELINE_TIMEOUT_MS = 30_000;
    private static final int TARGET_AGGREGATE_RESUBMIT_RATE_PER_SEC = 50;

    private long resubmitPacingMs() {
        return confirmAttemptPoolSize() * 1000L / TARGET_AGGREGATE_RESUBMIT_RATE_PER_SEC;
    }


    @DynamicPropertySource
    static void tossProps(DynamicPropertyRegistry registry) {
        registry.add("server.tomcat.mbeanregistry.enabled", () -> true);
        registry.add("spring.cloud.openfeign.client.config.tossPaymentsClient.read-timeout",
                () -> TOSS_READ_TIMEOUT_MS);
        registry.add("management.endpoints.web.exposure.include", () -> "health,metrics");
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
    @Autowired
    private MeterRegistry meterRegistry;

    private final BlockingQueue<String> availableTokens = new LinkedBlockingQueue<>();

    private final BlockingQueue<PreparedPayment> preparedPayments = new LinkedBlockingQueue<>();

    private final AtomicInteger maxConfirmInFlight = new AtomicInteger();

    record PreparedPayment(String accessToken, Long paymentId) {}
    private Long productId;

    protected int customerPoolSize() {
        return confirmAttemptPoolSize() * 3 + 10;
    }

    @BeforeEach
    void setUpWorkerOccupancyTest() {
        Store store = seeder.seedStoreWithProducts(1, 1000);

        availableTokens.clear();
        for (int i = 0; i < customerPoolSize(); i++) {
            availableTokens.add(createCustomerToken("cb-occupancy-" + System.nanoTime() + "-" + i + "@test.com"));
        }

        productId = productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().get(0).getId();

        preparedPayments.clear();
        for (int i = 0; i < confirmAttemptPoolSize(); i++) {
            String token = availableTokens.poll();
            preparedPayments.add(new PreparedPayment(token, prepareNewPayment(token)));
        }

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

    record ConfirmAttemptResult(long startElapsedMs, long durationMs, boolean failFast, String outcome) {}

    record OccupancySample(
            long elapsedMs, double tomcatBusy, long wiremockConfirmCount, int jvmThreadsLive, int confirmInFlight) {}

    record CategoriesSample(long elapsedMs, long latencyMs) {}

    record StateTransitionEvent(long elapsedMs, CircuitBreaker.State from, CircuitBreaker.State to) {}

    private void awaitIdleBaseline(String label) {
        double busy = Double.NaN;
        long deadline = System.currentTimeMillis() + BASELINE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            busy = readGaugeOrNan("tomcat.threads.busy");
            if (!Double.isNaN(busy) && busy <= BASELINE_BUSY_THRESHOLD) {
                break;
            }
            sleepQuietly(POLL_INTERVAL_MS);
        }
        double connections = readGaugeOrNan("hikaricp.connections.active");
        System.out.printf("[%s] 측정 시작 baseline — tomcat.threads.busy=%.1f, hikaricp.connections.active=%.1f%n",
                label, busy, connections);
        assertThat(busy)
                .describedAs("[%s] 셋업 잔여 부하가 가라앉은 뒤 측정을 시작해야 한다", label)
                .isLessThanOrEqualTo(BASELINE_BUSY_THRESHOLD);
    }

    protected void runLoadAndPrintReport(String label) throws Exception {
        awaitIdleBaseline(label);

        long testStart = System.currentTimeMillis();
        List<StateTransitionEvent> transitions = new CopyOnWriteArrayList<>();

        if (circuitBreakerFactory instanceof Resilience4JCircuitBreakerFactory real) {
            registerStateTransitionListenerAfterFirstRun(real, transitions, testStart);
        }

        maxConfirmInFlight.set(0);
        List<ConfirmAttemptResult> confirmResults = new CopyOnWriteArrayList<>();
        List<OccupancySample> occupancySamples = new CopyOnWriteArrayList<>();
        List<CategoriesSample> categoriesSamples = new CopyOnWriteArrayList<>();
        AtomicBoolean keepRunning = new AtomicBoolean(true);
        AtomicInteger confirmInFlight = new AtomicInteger();

        int confirmAttemptPoolSize = confirmAttemptPoolSize();
        ExecutorService confirmPool = Executors.newFixedThreadPool(confirmAttemptPoolSize);
        ExecutorService categoriesPool = Executors.newFixedThreadPool(2);
        ExecutorService pollerPool = Executors.newSingleThreadExecutor();

        try {
            for (int i = 0; i < confirmAttemptPoolSize; i++) {
                submitNextConfirmAttempt(confirmPool, keepRunning, confirmResults, confirmInFlight, testStart);
            }

            for (int i = 0; i < 2; i++) {
                categoriesPool.submit(() -> {
                    while (keepRunning.get()) {
                        long start = System.currentTimeMillis();
                        try {
                            restTemplate.getForEntity("/api/stores/categories", String.class);
                        } catch (Exception ignored) {
                        }
                        long latency = System.currentTimeMillis() - start;
                        categoriesSamples.add(new CategoriesSample(start - testStart, latency));
                        sleepQuietly(CATEGORIES_INTERVAL_MS);
                    }
                });
            }

            pollerPool.submit(() -> {
                while (keepRunning.get()) {
                    long elapsed = System.currentTimeMillis() - testStart;
                    double busy = readGaugeOrNan("tomcat.threads.busy");
                    long wiremockCount =
                            toss.server.findAll(postRequestedFor(urlPathMatching("/v1/payments/confirm"))).size();
                    int jvmThreads = Thread.activeCount();
                    occupancySamples.add(new OccupancySample(
                            elapsed, busy, wiremockCount, jvmThreads, confirmInFlight.get()));
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

        double maxBusyObserved = occupancySamples.stream()
                .mapToDouble(OccupancySample::tomcatBusy)
                .filter(v -> !Double.isNaN(v))
                .max().orElse(0);
        assertThat(maxBusyObserved)
                .as("[%s] 톰캣 워커가 실제로 압박받았어야 한다(재현 성립 여부 negative check)", label)
                .isGreaterThanOrEqualTo(tomcatMaxThreads() - 1);

        int maxInFlightObserved = maxConfirmInFlight.get();
        int minAcceptableInFlight = (int) Math.ceil(confirmAttemptPoolSize() * 0.9);
        assertThat(maxInFlightObserved)
                .as("[%s] 부하 생성기가 설정한 동시성(%d)에 실제로 도달했어야 한다"
                        + "(클라이언트/DB 풀이 병목이 아님을 확인)", label, confirmAttemptPoolSize())
                .isGreaterThanOrEqualTo(minAcceptableInFlight);
    }

    private void registerStateTransitionListenerAfterFirstRun(
            Resilience4JCircuitBreakerFactory factory, List<StateTransitionEvent> sink, long testStart) {
        // find()로 실제 호출이 생성한 회로를 기다린다.
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
            List<ConfirmAttemptResult> results, AtomicInteger confirmInFlight, long testStart) {
        if (!keepRunning.get() || pool.isShutdown()) {
            return;
        }
        pool.submit(() -> {
            if (!keepRunning.get()) {
                return;
            }
            PreparedPayment prepared = preparedPayments.poll();
            String accessToken = prepared != null ? prepared.accessToken() : availableTokens.poll();
            if (accessToken == null) {
                results.add(new ConfirmAttemptResult(
                        System.currentTimeMillis() - testStart, 0, true, "NO_CUSTOMER_AVAILABLE"));
                sleepQuietly(resubmitPacingMs());
                submitNextConfirmAttempt(pool, keepRunning, results, confirmInFlight, testStart);
                return;
            }

            long start = System.currentTimeMillis();
            String outcome;
            try {
                Long paymentId = prepared != null ? prepared.paymentId() : prepareNewPayment(accessToken);
                maxConfirmInFlight.accumulateAndGet(confirmInFlight.incrementAndGet(), Math::max);
                try {
                    callConfirm(accessToken, paymentId);
                } finally {
                    confirmInFlight.decrementAndGet();
                }
                outcome = "COMPLETED";
            } catch (Exception e) {
                outcome = e.getClass().getSimpleName();
            }
            long duration = System.currentTimeMillis() - start;
            boolean failFast = duration < 500;

            if (failFast) {
                availableTokens.offer(accessToken);
            }
            results.add(new ConfirmAttemptResult(
                    start - testStart, duration, failFast, outcome));
            if (failFast) {
                sleepQuietly(resubmitPacingMs());
            }
            submitNextConfirmAttempt(pool, keepRunning, results, confirmInFlight, testStart);
        });
    }

    private String createCustomerToken(String email) {
        seeder.seedUserWithAddress(email, "password1234!");
        LoginRequest loginRequest = new LoginRequest();
        ReflectionTestUtils.setField(loginRequest, "email", email);
        ReflectionTestUtils.setField(loginRequest, "password", "password1234!");
        ResponseEntity<ApiResponse<LoginResponse>> loginResponse = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(loginRequest),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        return loginResponse.getBody().getData().getAccessToken();
    }

    private Long prepareNewPayment(String accessToken) {
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

    private void callConfirm(String accessToken, Long paymentId) {
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
            Gauge gauge = meterRegistry.find(metricName).gauge();
            return gauge != null ? gauge.value() : Double.NaN;
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
