package com.example.finalproject.payment.service;

import com.example.finalproject.global.component.UserLoader;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.PaymentMethod;
import com.example.finalproject.payment.dto.request.PostBillingKeyIssueRequest;
import com.example.finalproject.payment.dto.request.TossBillingKeyIssueRequest;
import com.example.finalproject.payment.dto.response.GetPaymentMethodResponse;
import com.example.finalproject.payment.dto.response.PostBillingKeyIssueResponse;
import com.example.finalproject.payment.dto.response.TossBillingKeyIssueResponse;
import com.example.finalproject.payment.enums.CardIssuer;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.repository.PaymentMethodRepository;
import com.example.finalproject.user.domain.User;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BillingService {

    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentMethodRepository paymentMethodRepository;
    private final UserLoader userLoader;

    @Transactional
    public PostBillingKeyIssueResponse issueCardBillingKey(
            String email,
            PostBillingKeyIssueRequest request) {

        User user = userLoader.loadUserByUsername(email);

        TossBillingKeyIssueResponse response =
                tossPaymentsClient.issueBillingKey(
                        request.getAuthKey(),
                        new TossBillingKeyIssueRequest(request.getCustomerKey()));

        boolean hasDefaultPaymentMethod =
                paymentMethodRepository.existsByUserAndIsDefaultTrue(user);

        PaymentMethod paymentMethod = PaymentMethod.builder()
                .user(user)
                .methodType(PaymentMethodType.CARD)
                .billingKey(response.getBillingKey())
                .customerKey(response.getCustomerKey())
                .cardCompany(CardIssuer.getKoreanNameByCode(response.getCard().getIssuerCode()))
                .cardNumberMasked(response.getCard().getNumber())
                .isDefault(!hasDefaultPaymentMethod)
                .build();

        paymentMethodRepository.save(paymentMethod);

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

    @Transactional
    public void deletePaymentMethod(String email, Long paymentMethodId) {

        User user = userLoader.loadUserByUsername(email);

        // 사용자 소유권 검증 포함하여 조회
        PaymentMethod paymentMethod = paymentMethodRepository
                .findByIdAndUser_Id(paymentMethodId, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_METHOD_NOT_FOUND));

        tossPaymentsClient.deleteBillingKey(paymentMethod.getBillingKey());

        paymentMethodRepository.deleteById(paymentMethodId);

    }
}


