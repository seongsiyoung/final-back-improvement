package com.example.finalproject.payment.client.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.payment.config.TossPaymentsProperties;
import feign.Request;
import org.junit.jupiter.api.Test;

class TossFeignConfigTest {

    private final TossFeignConfig tossFeignConfig = new TossFeignConfig();

    @Test
    void tossRequestOptions_usesConfiguredTimeouts_fromProperties() {
        TossPaymentsProperties props = new TossPaymentsProperties();
        props.setConnectTimeoutMs(1234);
        props.setReadTimeoutMs(5678);

        Request.Options options = tossFeignConfig.tossRequestOptions(props);

        assertThat(options.connectTimeoutMillis()).isEqualTo(1234);
        assertThat(options.readTimeoutMillis()).isEqualTo(5678);
        assertThat(options.isFollowRedirects()).isFalse();
    }

    @Test
    void tossRequestOptions_usesFieldDefaults_whenPropertiesNotExplicitlySet() {
        // yml에 값이 없으면(예: application-local.yml 미설정) TossPaymentsProperties의
        // 자바 필드 기본값(1000/3000)이 그대로 쓰여야 한다 — int 기본값 0(=사실상 무제한)이
        // 되면 안 된다.
        TossPaymentsProperties props = new TossPaymentsProperties();

        Request.Options options = tossFeignConfig.tossRequestOptions(props);

        assertThat(options.connectTimeoutMillis()).isEqualTo(1000);
        assertThat(options.readTimeoutMillis()).isEqualTo(3000);
    }
}
