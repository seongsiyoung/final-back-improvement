package com.example.finalproject.moderation.service.admin.store;

import com.example.finalproject.communication.enums.NotificationRefType;
import com.example.finalproject.communication.service.NotificationService;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.moderation.domain.Approval;
import com.example.finalproject.moderation.domain.ApprovalDocument;
import com.example.finalproject.moderation.dto.admin.store.AdminStoreApprovalDetailResponse;
import com.example.finalproject.moderation.dto.admin.store.AdminStoreApprovalListResponse;
import com.example.finalproject.moderation.enums.ApplicantType;
import com.example.finalproject.moderation.enums.ApprovalStatus;
import com.example.finalproject.moderation.repository.ApprovalDocumentRepository;
import com.example.finalproject.moderation.repository.ApprovalRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.store.repository.StoreRepository;
import com.example.finalproject.user.domain.Role;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.domain.UserRole;
import com.example.finalproject.user.repository.RoleRepository;
import com.example.finalproject.user.repository.UserRepository;
import com.example.finalproject.user.repository.UserRoleRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminStoreApprovalService {

    private static final int HOLD_DAYS = 7;

    private final ApprovalRepository approvalRepository;
    private final ApprovalDocumentRepository approvalDocumentRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public List<AdminStoreApprovalListResponse> getStoreApprovals(List<ApprovalStatus> statuses) {
        List<ApprovalStatus> targetStatuses = (statuses == null || statuses.isEmpty())
                ? List.of(ApprovalStatus.PENDING, ApprovalStatus.HELD)
                : statuses;

        List<Approval> approvals = approvalRepository.findByApplicantTypeAndStatusIn(
                ApplicantType.STORE, targetStatuses);
        List<AdminStoreApprovalListResponse> result = new ArrayList<>();

        for (Approval approval : approvals) {
            Store store = storeRepository.findByOwnerId(approval.getUser().getId())
                    .orElse(null);
            if (store == null) {
                continue;
            }
            result.add(new AdminStoreApprovalListResponse(
                    approval.getId(),
                    store.getId(),
                    store.getStoreName(),
                    approval.getUser().getId(),
                    approval.getUser().getName(),
                    approval.getUser().getEmail(),
                    StringUtils.hasText(approval.getUser().getPhone())
                            ? approval.getUser().getPhone()
                            : store.getRepresentativePhone(),
                    store.getRepresentativePhone(),
                    approval.getStatus(),
                    approval.getCreatedAt(),
                    approval.getHeldUntil()
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public AdminStoreApprovalDetailResponse getStoreApprovalDetail(Long approvalId) {
        Approval approval = approvalRepository.findByIdAndApplicantType(approvalId, ApplicantType.STORE)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE));

        Store store = storeRepository.findByOwnerId(approval.getUser().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE));

        List<ApprovalDocument> documents = approvalDocumentRepository.findByApprovalId(approvalId);
        List<AdminStoreApprovalDetailResponse.DocumentInfo> documentInfos = new ArrayList<>();
        for (ApprovalDocument doc : documents) {
            documentInfos.add(new AdminStoreApprovalDetailResponse.DocumentInfo(
                    doc.getDocumentType(),
                    doc.getDocumentUrl()
            ));
        }

        String businessNumber = store.getSubmittedDocumentInfo() != null
                ? store.getSubmittedDocumentInfo().getBusinessNumber()
                : null;
        String businessOwnerName = store.getSubmittedDocumentInfo() != null
                ? store.getSubmittedDocumentInfo().getBusinessOwnerName()
                : null;
        String telecomSalesReportNumber = store.getSubmittedDocumentInfo() != null
                ? store.getSubmittedDocumentInfo().getTelecomSalesReportNumber()
                : null;
        String addressLine1 = store.getAddress() != null
                ? store.getAddress().getAddressLine1()
                : null;
        String addressLine2 = store.getAddress() != null
                ? store.getAddress().getAddressLine2()
                : null;
        String categoryName = store.getStoreCategory() != null
                ? store.getStoreCategory().getCategoryName()
                : null;
        String settlementBankName = store.getSettlementAccount() != null
                ? store.getSettlementAccount().getBankName()
                : null;
        String settlementBankAccount = store.getSettlementAccount() != null
                ? store.getSettlementAccount().getBankAccount()
                : null;
        String settlementAccountHolder = store.getSettlementAccount() != null
                ? store.getSettlementAccount().getAccountHolder()
                : null;

        AdminStoreApprovalDetailResponse.StoreInfo storeInfo =
                new AdminStoreApprovalDetailResponse.StoreInfo(
                        store.getId(),
                        store.getStoreName(),
                        categoryName,
                        businessOwnerName,
                        businessNumber,
                        telecomSalesReportNumber,
                        store.getRepresentativeName(),
                        store.getRepresentativePhone(),
                        addressLine1,
                        addressLine2,
                        settlementBankName,
                        settlementBankAccount,
                        settlementAccountHolder
                );

        return new AdminStoreApprovalDetailResponse(
                approval.getId(),
                approval.getStatus(),
                approval.getReason(),
                approval.getCreatedAt(),
                approval.getApprovedAt(),
                approval.getHeldUntil(),
                storeInfo,
                documentInfos
        );
    }

    public void approveStore(Long approvalId, String adminEmail) {
        Approval approval = getStoreApprovalForDecision(approvalId);
        validateStatusForApprove(approval);

        User admin = getAdminUser(adminEmail);
        Store store = getStoreByApproval(approval);

        approval.approve(admin);
        store.approve();
        grantRole(approval.getUser(), "STORE");

        notificationService.createNotification(
                approval.getUser().getId(),
                "마트 승인 완료",
                "마트 신청이 승인되었습니다.",
                NotificationRefType.STORE
        );
    }

    public void holdStore(Long approvalId, String adminEmail, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Approval approval = getStoreApprovalForDecision(approvalId);
        if (approval.getStatus() != ApprovalStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        User admin = getAdminUser(adminEmail);
        LocalDateTime heldUntil = LocalDateTime.now().plusDays(HOLD_DAYS);
        approval.hold(admin, reason, heldUntil);

        notificationService.createNotification(
                approval.getUser().getId(),
                "마트 신청 보류",
                "마트 신청이 보류되었습니다. 사유: " + reason,
                NotificationRefType.STORE
        );
    }

    public void rejectStore(Long approvalId, String adminEmail, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Approval approval = getStoreApprovalForDecision(approvalId);
        if (approval.getStatus() != ApprovalStatus.PENDING
                && approval.getStatus() != ApprovalStatus.HELD) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        User admin = getAdminUser(adminEmail);
        Store store = getStoreByApproval(approval);

        approval.reject(admin, reason);
        store.reject();

        notificationService.createNotification(
                approval.getUser().getId(),
                "마트 신청 거절",
                "마트 신청이 거절되었습니다. 사유: " + reason,
                NotificationRefType.STORE
        );
    }

    private Approval getStoreApprovalForDecision(Long approvalId) {
        return approvalRepository.findByIdAndApplicantType(approvalId, ApplicantType.STORE)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE));
    }

    private Store getStoreByApproval(Approval approval) {
        return storeRepository.findByOwnerId(approval.getUser().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE));
    }

    private User getAdminUser(String adminEmail) {
        if (!StringUtils.hasText(adminEmail)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        User admin = userRepository.findByEmail(adminEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!admin.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_AUTHORITY_REQUIRED);
        }
        return admin;
    }

    private void grantRole(User user, String roleName) {
        Role role = roleRepository.findByRoleName(roleName)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE));
        if (!userRoleRepository.existsByUserIdAndRoleId(user.getId(), role.getId())) {
            userRoleRepository.save(new UserRole(user, role));
        }
    }

    private void validateStatusForApprove(Approval approval) {
        ApprovalStatus status = approval.getStatus();
        if (!Objects.equals(status, ApprovalStatus.PENDING)
                && !Objects.equals(status, ApprovalStatus.HELD)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
