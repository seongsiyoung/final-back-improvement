package com.example.finalproject.moderation.service.admin.rider;

import com.example.finalproject.communication.enums.NotificationRefType;
import com.example.finalproject.communication.service.NotificationService;
import com.example.finalproject.delivery.domain.Rider;
import com.example.finalproject.delivery.repository.RiderRepository;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.moderation.domain.Approval;
import com.example.finalproject.moderation.domain.ApprovalDocument;
import com.example.finalproject.moderation.dto.admin.rider.AdminRiderApprovalDetailResponse;
import com.example.finalproject.moderation.dto.admin.rider.AdminRiderApprovalListResponse;
import com.example.finalproject.moderation.enums.ApplicantType;
import com.example.finalproject.moderation.enums.ApprovalStatus;
import com.example.finalproject.moderation.repository.ApprovalDocumentRepository;
import com.example.finalproject.moderation.repository.ApprovalRepository;
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
public class AdminRiderApprovalService {

    private static final int HOLD_DAYS = 7;

    private final ApprovalRepository approvalRepository;
    private final ApprovalDocumentRepository approvalDocumentRepository;
    private final RiderRepository riderRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public List<AdminRiderApprovalListResponse> getRiderApprovals(List<ApprovalStatus> statuses) {
        List<ApprovalStatus> targetStatuses = (statuses == null || statuses.isEmpty())
                ? List.of(ApprovalStatus.PENDING, ApprovalStatus.HELD)
                : statuses;

        List<Approval> approvals = approvalRepository.findByApplicantTypeAndStatusIn(
                ApplicantType.RIDER, targetStatuses);
        List<AdminRiderApprovalListResponse> result = new ArrayList<>();

        for (Approval approval : approvals) {
            Rider rider = riderRepository.findByUserId(approval.getUser().getId())
                    .orElse(null);
            if (rider == null) {
                continue;
            }
            result.add(new AdminRiderApprovalListResponse(
                    approval.getId(),
                    rider.getId(),
                    approval.getUser().getId(),
                    rider.getDisplayName(),
                    approval.getUser().getEmail(),
                    StringUtils.hasText(approval.getUser().getPhone())
                            ? approval.getUser().getPhone()
                            : rider.getDisplayPhone(),
                    approval.getStatus(),
                    approval.getCreatedAt(),
                    approval.getHeldUntil()
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public AdminRiderApprovalDetailResponse getRiderApprovalDetail(Long approvalId) {
        Approval approval = approvalRepository.findByIdAndApplicantType(approvalId, ApplicantType.RIDER)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE));

        Rider rider = riderRepository.findByUserId(approval.getUser().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE));

        List<ApprovalDocument> documents = approvalDocumentRepository.findByApprovalId(approvalId);
        List<AdminRiderApprovalDetailResponse.DocumentInfo> documentInfos = new ArrayList<>();
        for (ApprovalDocument doc : documents) {
            documentInfos.add(new AdminRiderApprovalDetailResponse.DocumentInfo(
                    doc.getDocumentType(),
                    doc.getDocumentUrl()
            ));
        }

        AdminRiderApprovalDetailResponse.RiderInfo riderInfo =
                new AdminRiderApprovalDetailResponse.RiderInfo(
                        rider.getId(),
                        approval.getUser().getId(),
                        rider.getDisplayName(),
                        rider.getDisplayPhone(),
                        rider.getIdCardVerified(),
                        rider.getBankName(),
                        rider.getBankAccount(),
                        rider.getAccountHolder()
                );

        return new AdminRiderApprovalDetailResponse(
                approval.getId(),
                approval.getStatus(),
                approval.getReason(),
                approval.getCreatedAt(),
                approval.getApprovedAt(),
                approval.getHeldUntil(),
                riderInfo,
                documentInfos
        );
    }

    public void approveRider(Long approvalId, String adminEmail) {
        Approval approval = getRiderApprovalForDecision(approvalId);
        validateStatusForApprove(approval);

        User admin = getAdminUser(adminEmail);
        Rider rider = getRiderByApproval(approval);

        approval.approve(admin);
        rider.approve();
        grantRole(approval.getUser(), "RIDER");

        notificationService.createNotification(
                approval.getUser().getId(),
                "배달원 승인 완료",
                "배달원 신청이 승인되었습니다.",
                NotificationRefType.RIDER
        );
    }

    public void holdRider(Long approvalId, String adminEmail, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Approval approval = getRiderApprovalForDecision(approvalId);
        if (approval.getStatus() != ApprovalStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        User admin = getAdminUser(adminEmail);
        LocalDateTime heldUntil = LocalDateTime.now().plusDays(HOLD_DAYS);
        approval.hold(admin, reason, heldUntil);

        notificationService.createNotification(
                approval.getUser().getId(),
                "배달원 신청 보류",
                "배달원 신청이 보류되었습니다. 사유: " + reason,
                NotificationRefType.RIDER
        );
    }

    public void rejectRider(Long approvalId, String adminEmail, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Approval approval = getRiderApprovalForDecision(approvalId);
        if (approval.getStatus() != ApprovalStatus.PENDING
                && approval.getStatus() != ApprovalStatus.HELD) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        User admin = getAdminUser(adminEmail);
        Rider rider = getRiderByApproval(approval);

        approval.reject(admin, reason);
        rider.reject();

        notificationService.createNotification(
                approval.getUser().getId(),
                "배달원 신청 거절",
                "배달원 신청이 거절되었습니다. 사유: " + reason,
                NotificationRefType.RIDER
        );
    }

    private Approval getRiderApprovalForDecision(Long approvalId) {
        return approvalRepository.findByIdAndApplicantType(approvalId, ApplicantType.RIDER)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE));
    }

    private Rider getRiderByApproval(Approval approval) {
        return riderRepository.findByUserId(approval.getUser().getId())
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
