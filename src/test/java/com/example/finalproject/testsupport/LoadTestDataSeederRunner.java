package com.example.finalproject.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 수동 실행 전용. {@code @Test}이지만 assert 없이 시딩만 수행하고 끝나는 일회성 클래스다.
 *
 * <p>k6는 JVM 밖에서 실행되므로 {@link LoadTestDataSeeder}를 직접 호출할 수 없다. 이 클래스를 한 번 돌려
 * k6 부하 테스트용 고정 계정/매장/상품을 만들어 둔다.
 *
 * <p>주의: {@link IntegrationTestSupport}를 상속하므로 Testcontainers를 띄운다. k6로 실제 부하를 걸 때는
 * 이 컨테이너가 아니라 {@code test} 프로파일로 직접 기동한 서버(로컬 Docker Postgres/Redis)를 대상으로 한다.
 * 자세한 내용은 {@code k6/README.md} 참고.
 *
 * <p>실행: {@code ./gradlew test --tests '...LoadTestDataSeederRunner'}
 */
class LoadTestDataSeederRunner extends IntegrationTestSupport {

    @Autowired
    private LoadTestDataSeeder seeder;

    @Test
    void seed() {
        seeder.seedUserWithAddress("k6-load-user@test.com", "loadtest1234!");
        seeder.seedStoreWithProducts(20, 100_000);
    }
}
