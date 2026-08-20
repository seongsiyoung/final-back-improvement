package com.example.finalproject.payment.service;

import com.example.finalproject.global.component.UserLoader;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.config.TossCircuitBreakerFallback;
import com.example.finalproject.payment.domain.PaymentMethod;
import com.example.finalproject.payment.dto.request.PostBillingKeyIssueRequest;
import com.example.finalproject.payment.dto.request.TossBillingKeyIssueRequest;
import com.example.finalproject.payment.dto.response.GetPaymentMethodResponse;
import com.example.finalproject.payment.dto.response.PostBillingKeyIssueResponse;
import com.example.finalproject.payment.dto.response.TossBillingKeyIssueResponse;
import com.example.finalproject.payment.repository.PaymentMethodRepository;
import com.example.finalproject.user.domain.User;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentMethodRepository paymentMethodRepository;
    private final UserLoader userLoader;
    private final BillingKeyCommandService billingKeyCommandService;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public PostBillingKeyIssueResponse issueCardBillingKey(
            String email,
            PostBillingKeyIssueRequest request) {

        User user = userLoader.loadUserByUsername(email);
        BillingKeyCommandService.BillingIssuePreparation prep = billingKeyCommandService.prepareIssue(user);

        TossBillingKeyIssueResponse response = circuitBreakerFactory.create("toss-billing")
                .run(() -> tossPaymentsClient.issueBillingKey(
                                request.getAuthKey(), new TossBillingKeyIssueRequest(request.getCustomerKey())),
                        TossCircuitBreakerFallback::rethrow);

        PaymentMethod paymentMethod;
        try {
            paymentMethod = billingKeyCommandService.completeIssue(
                    prep.user(), prep.hasDefaultPaymentMethod(), response);
        } catch (RuntimeException e) {
            try {
                circuitBreakerFactory.create("toss-billing")
                        .run(() -> { tossPaymentsClient.deleteBillingKey(response.getBillingKey()); return null; },
                                TossCircuitBreakerFallback::rethrow);
            } catch (RuntimeException compensationFailure) {
                log.error("빌링키 발급 저장 실패 후 보상(deleteBillingKey)도 실패함. billingKey={}",
                        response.getBillingKey(), compensationFailure);
                e.addSuppressed(compensationFailure);
            }
            throw e;
        }

        return new PostBillingKeyIssueResponse(
                paymentMethod.getCardCompany(),
                paymentMethod.getCardNumberMasked()
        );
    }

    /**
     * 사용자의 결제 수단 목록 조회
     */
    @Transactional(readOnly = true)
    public List<GetPaymentMethodResponse> getMyPaymentMethods(String email) {
        User user = userLoader.loadUserByUsername(email);
        return paymentMethodRepository.findByUserOrderByIsDefaultDesc(user)
                .stream()
                .map(GetPaymentMethodResponse::new)
                .toList();
    }

    /**
     * 기본 결제 수단 설정
     */
    @Transactional
    public void setDefaultPaymentMethod(String email, Long paymentMethodId) {
        User user = userLoader.loadUserByUsername(email);

        PaymentMethod targetMethod = paymentMethodRepository
                .findByIdAndUser_Id(paymentMethodId, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_METHOD_NOT_FOUND));

        if (targetMethod.isDefault()) {
            return;
        }

        paymentMethodRepository.findFirstByUserIdAndIsDefaultTrue(user.getId())
                .ifPresent(PaymentMethod::unsetAsDefault);

        targetMethod.setAsDefault();
    }

    public void deletePaymentMethod(String email, Long paymentMethodId) {
        String billingKey = billingKeyCommandService.loadForDelete(email, paymentMethodId);
        circuitBreakerFactory.create("toss-billing")
                .run(() -> { tossPaymentsClient.deleteBillingKey(billingKey); return null; }, TossCircuitBreakerFallback::rethrow);
        try {
            billingKeyCommandService.completeDelete(paymentMethodId);
        } catch (RuntimeException e) {
            log.error("Toss 빌링키 삭제는 성공했으나 로컬 결제수단 삭제 반영에 실패함. paymentMethodId={}",
                    paymentMethodId, e);
            throw e;
        }
    }
}


