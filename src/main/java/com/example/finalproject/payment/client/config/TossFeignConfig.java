package com.example.finalproject.payment.client.config;


import com.example.finalproject.payment.config.TossPaymentsProperties;
import feign.Request;
import feign.RequestInterceptor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
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
     * feign.client.config.tossPaymentsClient.*(application.yml) 기반 타임아웃 설정을 무시하고
     * feign 기본값(connect 10s / read 60s)으로 덮어쓴다. 같은 yml 값을 그대로 읽어 Options 빈을
     * 직접 등록해 이 문제를 우회한다 — yml이 여전히 유일한 값 출처다.
     */
    @Bean
    public Request.Options tossRequestOptions(
            @Value("${feign.client.config.tossPaymentsClient.connect-timeout}") int connectTimeoutMs,
            @Value("${feign.client.config.tossPaymentsClient.read-timeout}") int readTimeoutMs) {
        return new Request.Options(
                connectTimeoutMs, TimeUnit.MILLISECONDS,
                readTimeoutMs, TimeUnit.MILLISECONDS,
                false);
    }
}
