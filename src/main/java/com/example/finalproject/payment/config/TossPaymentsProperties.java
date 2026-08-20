package com.example.finalproject.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "toss.payments")
public class TossPaymentsProperties {
    private String baseUrl;
    private String secretKey;
    // yml에서 값을 안 채우면 int 기본값(0)이 되어 타임아웃이 사실상 무제한이 될 위험이 있다 —
    // application-local.yml처럼 저장소에 없는(gitignore) 프로파일이 값을 빠뜨려도 안전하도록
    // 필드 기본값을 둔다.
    private int connectTimeoutMs = 1000;
    private int readTimeoutMs = 3000;
}
