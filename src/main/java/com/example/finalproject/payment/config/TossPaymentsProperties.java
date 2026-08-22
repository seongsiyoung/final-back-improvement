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
}
