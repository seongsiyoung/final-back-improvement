package com.example.finalproject.payment.client.config;


import com.example.finalproject.payment.config.TossPaymentsProperties;
import feign.Request;
import feign.RequestInterceptor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;

public class TossFeignConfig {
    @Bean
    public RequestInterceptor tossAuthInterceptor(TossPaymentsProperties props) {
        return template -> {
            String raw = props.getSecretKey() + ":";
            String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            template.header("Authorization", "Basic " + encoded);
            template.header("Content-Type", "application/json");
        };
    }

    /**
     * @FeignClient(configuration = ...)로 커스텀 설정 클래스를 지정하면, Spring Cloud OpenFeign이
     * feign.client.config.* 기반 자동 설정을 적용하지 않고 feign 기본값(connect 10s / read 60s)으로
     * 덮어쓴다(Task 5에서 실측으로 확인). 그래서 Request.Options 빈을 직접 등록해야 하는데, 값 자체는
     * TossPaymentsProperties(toss.payments.*, 이 클래스 전용 프로퍼티임이 이름에서부터 명확함)에서
     * 가져온다 — feign.client.config.* 이름을 재사용하면 "Spring Cloud가 자동으로 읽어갈 것"이라는
     * 착각을 유발하기 쉽다.
     */
    @Bean
    public Request.Options tossRequestOptions(TossPaymentsProperties props) {
        return new Request.Options(
                props.getConnectTimeoutMs(), TimeUnit.MILLISECONDS,
                props.getReadTimeoutMs(), TimeUnit.MILLISECONDS,
                false);
    }
}
