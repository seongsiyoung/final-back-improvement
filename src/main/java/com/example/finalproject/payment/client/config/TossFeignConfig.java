package com.example.finalproject.payment.client.config;


import com.example.finalproject.payment.config.TossPaymentsProperties;
import feign.RequestInterceptor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
}
