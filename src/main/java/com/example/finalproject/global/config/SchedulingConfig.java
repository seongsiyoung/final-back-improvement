package com.example.finalproject.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러 자동 실행은 test 프로파일에서 끈다.
 *
 * <p>통합 테스트는 static Testcontainers 로 DB 를 공유하고, Spring 은 테스트 컨텍스트를
 * 캐시해 JVM 이 끝날 때까지 살려둔다. 이미 끝난 테스트의 컨텍스트에 남아 있는 스케줄러
 * 스레드가 지금 돌고 있는 테스트의 행을 재조정해 상태와 재고를 바꾼다.
 *
 * <p>스케줄러 테스트는 @Scheduled 주기를 기다리지 않고 메서드를 직접 부르므로 잃는 것이 없다.
 */
@Configuration
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
