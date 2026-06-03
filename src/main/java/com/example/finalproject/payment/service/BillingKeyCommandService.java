package com.example.finalproject.payment.service;

import com.example.finalproject.global.component.UserLoader;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.domain.PaymentMethod;
import com.example.finalproject.payment.dto.response.TossBillingKeyIssueResponse;
import com.example.finalproject.payment.enums.CardIssuer;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.repository.PaymentMethodRepository;
import com.example.finalproject.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BillingKeyCommandService {

    private final PaymentMethodRepository paymentMethodRepository;
    private final UserLoader userLoader;

    public record BillingIssuePreparation(User user, boolean hasDefaultPaymentMethod) {}

    @Transactional(readOnly = true)
    public BillingIssuePreparation prepareIssue(User user) {
        boolean hasDefaultPaymentMethod = paymentMethodRepository.existsByUserAndIsDefaultTrue(user);
        return new BillingIssuePreparation(user, hasDefaultPaymentMethod);
    }

    @Transactional
    public PaymentMethod completeIssue(
            User user, boolean hasDefaultPaymentMethod, TossBillingKeyIssueResponse response) {

        PaymentMethod paymentMethod = PaymentMethod.builder()
                .user(user)
                .methodType(PaymentMethodType.CARD)
                .billingKey(response.getBillingKey())
                .customerKey(response.getCustomerKey())
                .cardCompany(CardIssuer.getKoreanNameByCode(response.getCard().getIssuerCode()))
                .cardNumberMasked(response.getCard().getNumber())
                .isDefault(!hasDefaultPaymentMethod)
                .build();

        return paymentMethodRepository.save(paymentMethod);
    }

    @Transactional(readOnly = true)
    public String loadForDelete(String email, Long paymentMethodId) {
        User user = userLoader.loadUserByUsername(email);
        PaymentMethod paymentMethod = paymentMethodRepository
                .findByIdAndUser_Id(paymentMethodId, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_METHOD_NOT_FOUND));
        return paymentMethod.getBillingKey();
    }

    @Transactional
    public void completeDelete(Long paymentMethodId) {
        paymentMethodRepository.deleteById(paymentMethodId);
    }
}
