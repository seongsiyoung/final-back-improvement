package com.example.finalproject.testsupport;

import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * {@link IntegrationTestSupport} 가 모든 통합 테스트에 공유하는 Toss 대역.
 *
 * <p>{@code TossStub} 을 그대로 base class 의 static 필드로 두면 상속한 클래스마다
 * {@code beforeAll} 이 다시 돌아 WireMock 이 재시작되고 {@code dynamicPort()} 라 포트가 바뀐다.
 * 그런데 컨텍스트는 캐시되어 한 번만 만들어지므로, 기동 시점에 {@code toss.payments.base-url}
 * 을 붙잡은 싱글턴(Feign 클라이언트)은 첫 포트에 묶인 채 남는다. 테스트 인스턴스의
 * {@code @Value} 는 Supplier 가 매번 평가되어 최신 포트를 받기 때문에 겉으로는 정상으로 보이고,
 * 실제 PG 호출만 죽은 포트로 간다.
 *
 * <p>그래서 컨테이너와 같은 방식을 쓴다 — JVM 당 한 번만 시작하고 JVM 종료까지 유지한다.
 * 대신 테스트 사이 격리는 {@code beforeEach} 에서 직접 초기화해 유지한다.
 */
public class SharedTossStub extends TossStub {

    private boolean started;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (started) {
            return;
        }
        started = true;
        super.beforeAll(context);
    }

    /** JVM 종료까지 유지한다. 클래스마다 내리면 포트가 바뀐다. */
    @Override
    public void afterAll(ExtensionContext context) {
    }

    /** {@code WireMockExtension} 의 기본 초기화를 대신한다 — 스텁과 요청 기록을 모두 지운다. */
    @Override
    public void beforeEach(ExtensionContext context) {
        server.resetAll();
    }

    @Override
    public void afterEach(ExtensionContext context) {
    }
}
