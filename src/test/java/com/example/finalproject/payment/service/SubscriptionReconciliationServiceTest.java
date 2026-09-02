package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.subscription.enums.SubscriptionStatus;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.SubscriptionScenarioSeeder;
import feign.FeignException;
import feign.Request;
import feign.Request.HttpMethod;
import feign.RequestTemplate;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;

class SubscriptionReconciliationServiceTest extends IntegrationTestSupport {

    private static final Request REQUEST = Request.create(
            HttpMethod.GET, "/v1/payments/orders/x", Collections.emptyMap(),
            new byte[0], StandardCharsets.UTF_8, new RequestTemplate());

    @Autowired
    private SubscriptionReconciliationService subscriptionReconciliationService;
    @Autowired
    private SubscriptionPaymentRepository subscriptionPaymentRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private SubscriptionScenarioSeeder subscriptionScenarioSeeder;
    @MockBean
    private TossPaymentsClient tossPaymentsClient;

    @Test
    @DisplayName("Toss가 승인했으면 구독 결제를 승인으로 확정하고 구독도 되살린다")
    void pending_whenApprovedAtPg_completesCharge() {
        SubscriptionPayment payment = stuck(PaymentStatus.PENDING);
        LocalDate beforeNextPaymentDate = subscriptionOf(payment).getNextPaymentDate();
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(done());

        subscriptionReconciliationService.reconcile(payment);

        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.APPROVED);
        assertThat(subscriptionOf(payment).getStatus())
                .as("결제만 확정하고 구독을 두면 매일 재시도 대상에 계속 오른다")
                .isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscriptionOf(payment).getNextPaymentDate()).isAfter(beforeNextPaymentDate);

        SubscriptionPayment approved = subscriptionPaymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(approved.getCardCompany())
                .as("조회 응답의 카드사는 코드가 아니라 이름 그대로 온다")
                .isEqualTo("테스트카드사");
        assertThat(approved.getCardNumberMasked()).isEqualTo("1234-****-****-5678");
    }

    @Test
    @DisplayName("Toss에 기록이 없으면 실패로 확정하고 구독은 건드리지 않는다")
    void pending_whenNotFoundAtPg_marksFailed() {
        SubscriptionPayment payment = stuck(PaymentStatus.PENDING);
        LocalDate beforeNextPaymentDate = subscriptionOf(payment).getNextPaymentDate();
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenThrow(notFound());

        subscriptionReconciliationService.reconcile(payment);

        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.FAILED);
        assertThat(subscriptionOf(payment).getStatus())
                .as("실패로 확정한 건은 다음 날 재시도돼야 하므로 PAYMENT_FAILED 로 남는다")
                .isEqualTo(SubscriptionStatus.PAYMENT_FAILED);
        assertThat(subscriptionOf(payment).getNextPaymentDate()).isEqualTo(beforeNextPaymentDate);
    }

    @Test
    @DisplayName("해지를 요청한 구독은 승인만 확정하고 되살리지 않는다")
    void pending_whenSubscriptionCancellationRequested_doesNotRevive() {
        SubscriptionPayment payment = stuck(PaymentStatus.PENDING);
        Subscription subscription = subscriptionOf(payment);
        subscription.requestCancellation("고객 해지 요청");
        subscriptionRepository.save(subscription);
        LocalDate beforeNextPaymentDate = subscription.getNextPaymentDate();
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(done());

        subscriptionReconciliationService.reconcile(payment);

        assertThat(statusOf(payment))
                .as("Toss 가 승인했으니 결제는 확정해야 한다")
                .isEqualTo(PaymentStatus.APPROVED);
        assertThat(subscriptionOf(payment).getStatus())
                .as("재조정이 사용자의 해지를 되돌리면 안 된다")
                .isEqualTo(SubscriptionStatus.CANCELLATION_PENDING);
        assertThat(subscriptionOf(payment).getNextPaymentDate()).isEqualTo(beforeNextPaymentDate);
    }

    @Test
    @DisplayName("Toss 상태가 DONE이 아니면 실패로 확정한다")
    void pending_whenNotDoneAtPg_marksFailed() {
        SubscriptionPayment payment = stuck(PaymentStatus.PENDING);
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(withStatus("ABORTED"));

        subscriptionReconciliationService.reconcile(payment);

        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("Toss 조회가 실패하면 예외를 잡지 않고 상태도 바꾸지 않는다")
    void pending_whenQueryFails_keepsState() {
        SubscriptionPayment payment = stuck(PaymentStatus.PENDING);
        when(tossPaymentsClient.getPaymentByOrderId(anyString()))
                .thenThrow(new RuntimeException("Toss 조회 실패"));

        assertThatThrownBy(() -> subscriptionReconciliationService.reconcile(payment))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(payment))
                .as("모르는 것을 실패로 확정하지 않아야 다음 주기에 다시 시도된다")
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("REVERSAL_PENDING 은 취소가 확인돼야 FAILED 로 간다")
    void reversalPending_whenCanceledAtPg_marksFailed() {
        SubscriptionPayment payment = stuck(PaymentStatus.REVERSAL_PENDING);
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(withStatus("CANCELED"));

        subscriptionReconciliationService.reconcile(payment);

        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("REVERSAL_PENDING 인데 취소가 확인되지 않으면 그대로 둔다")
    void reversalPending_whenNotCanceledAtPg_keepsState() {
        SubscriptionPayment payment = stuck(PaymentStatus.REVERSAL_PENDING);
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(done());

        subscriptionReconciliationService.reconcile(payment);

        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.REVERSAL_PENDING);
    }

    @Test
    @DisplayName("REVERSAL_PENDING 인데 PG 기록이 없으면 실패로 확정하지 않는다")
    void reversalPending_whenNotFoundAtPg_marksReconciliationRequired() {
        SubscriptionPayment payment = stuck(PaymentStatus.REVERSAL_PENDING);
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenThrow(notFound());

        subscriptionReconciliationService.reconcile(payment);

        assertThat(statusOf(payment))
                .as("승인 성공 뒤에만 붙는 상태라 기록 없음과 모순이다. 돈이 나갔을 수 있다")
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
    }

    private SubscriptionPayment stuck(PaymentStatus status) {
        return subscriptionScenarioSeeder.stuckSubscriptionPayment(
                "sub-reconcile-" + System.nanoTime() + "@test.com", status, 30);
    }

    private PaymentStatus statusOf(SubscriptionPayment payment) {
        return subscriptionPaymentRepository.findById(payment.getId()).orElseThrow().getPaymentStatus();
    }

    private Subscription subscriptionOf(SubscriptionPayment payment) {
        return subscriptionRepository.findById(payment.getSubscription().getId()).orElseThrow();
    }

    private TossConfirmResponse done() {
        TossConfirmResponse response = withStatus("DONE");
        TossConfirmResponse.Card card = new TossConfirmResponse.Card();
        ReflectionTestUtils.setField(card, "company", "테스트카드사");
        ReflectionTestUtils.setField(card, "number", "1234-****-****-5678");
        ReflectionTestUtils.setField(response, "card", card);
        return response;
    }

    private TossConfirmResponse withStatus(String status) {
        TossConfirmResponse response = new TossConfirmResponse();
        ReflectionTestUtils.setField(response, "paymentKey", "sub-reconcile-key-" + System.nanoTime());
        ReflectionTestUtils.setField(response, "status", status);
        return response;
    }

    private FeignException.NotFound notFound() {
        return new FeignException.NotFound("not found", REQUEST, new byte[0], Collections.emptyMap());
    }
}
