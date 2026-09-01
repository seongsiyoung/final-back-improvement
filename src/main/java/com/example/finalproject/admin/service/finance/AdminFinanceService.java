/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  lombok.Generated
 *  org.springframework.data.domain.Page
 *  org.springframework.data.domain.PageImpl
 *  org.springframework.data.domain.Pageable
 *  org.springframework.stereotype.Service
 *  org.springframework.transaction.annotation.Transactional
 */
package com.example.finalproject.admin.service.finance;

import com.example.finalproject.admin.dto.finance.AdminOverviewStatsResponse;
import com.example.finalproject.admin.dto.finance.AdminPaymentListResponse;
import com.example.finalproject.admin.dto.finance.AdminPaymentSummaryResponse;
import com.example.finalproject.admin.dto.finance.AdminRiderSettlementListResponse;
import com.example.finalproject.admin.dto.finance.AdminRiderSettlementSummaryResponse;
import com.example.finalproject.admin.dto.finance.AdminRiderSettlementTrendResponse;
import com.example.finalproject.admin.dto.finance.AdminStoreSettlementExecuteRequest;
import com.example.finalproject.admin.dto.finance.AdminStoreSettlementExecuteResponse;
import com.example.finalproject.admin.dto.finance.AdminStoreSettlementListResponse;
import com.example.finalproject.admin.dto.finance.AdminStoreSettlementSummaryResponse;
import com.example.finalproject.admin.dto.finance.AdminStoreSettlementTrendResponse;
import com.example.finalproject.admin.dto.finance.AdminTransactionDetailResponse;
import com.example.finalproject.admin.dto.finance.AdminTransactionOrderDetailResponse;
import com.example.finalproject.admin.dto.finance.AdminTransactionTrendResponse;
import com.example.finalproject.communication.enums.InquiryStatus;
import com.example.finalproject.communication.repository.InquiryRepository;
import com.example.finalproject.delivery.domain.Delivery;
import com.example.finalproject.delivery.domain.Rider;
import com.example.finalproject.delivery.enums.DeliveryStatus;
import com.example.finalproject.delivery.repository.DeliveryRepository;
import com.example.finalproject.delivery.repository.RiderRepository;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.moderation.enums.ReportStatus;
import com.example.finalproject.moderation.repository.ReportRepository;
import com.example.finalproject.order.domain.OrderProduct;
import com.example.finalproject.order.domain.OrderLine;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.enums.OrderType;
import com.example.finalproject.order.repository.OrderLineRepository;
import com.example.finalproject.order.repository.OrderProductRepository;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.settlement.domain.Settlement;
import com.example.finalproject.settlement.enums.SettlementStatus;
import com.example.finalproject.settlement.enums.SettlementTargetType;
import com.example.finalproject.settlement.rider.batch.RiderSettlementBatchLauncher;
import com.example.finalproject.settlement.store.batch.StoreSettlementBatchLauncher;
import com.example.finalproject.settlement.store.repository.SettlementRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.store.enums.StoreStatus;
import com.example.finalproject.store.repository.StoreRepository;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Generated;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class AdminFinanceService {
    private static final double PLATFORM_FEE_RATE = 0.05;
    private static final Set<PaymentStatus> COUNTABLE_PAYMENT_STATUSES = Set.of(PaymentStatus.APPROVED, PaymentStatus.PARTIAL_REFUNDED, PaymentStatus.REFUNDED, PaymentStatus.REFUND_REQUESTED);
    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final ReportRepository reportRepository;
    private final InquiryRepository inquiryRepository;
    private final RiderRepository riderRepository;
    private final DeliveryRepository deliveryRepository;
    private final OrderProductRepository orderProductRepository;
    private final OrderLineRepository orderLineRepository;
    private final StoreOrderRepository storeOrderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository paymentRefundRepository;
    private final SettlementRepository settlementRepository;
    private final StoreSettlementBatchLauncher settlementBatchLauncher;
    private final RiderSettlementBatchLauncher riderSettlementBatchLauncher;

    public AdminOverviewStatsResponse getOverviewStats(String adminEmail) {
        this.validateAdmin(adminEmail);
        return AdminOverviewStatsResponse.builder().totalUsers(this.userRepository.countByDeletedAtIsNull()).approvedStores(this.storeRepository.countByStatus(StoreStatus.APPROVED)).deliveringRiders(this.deliveryRepository.countDistinctRiderByStatus(DeliveryStatus.DELIVERING)).pendingStoreSettlements(this.settlementRepository.countByTargetTypeAndStatus(SettlementTargetType.STORE, SettlementStatus.PENDING)).pendingReports(this.reportRepository.countByStatus(ReportStatus.PENDING)).pendingInquiries(this.inquiryRepository.countByStatus(InquiryStatus.PENDING)).build();
    }

    public AdminTransactionTrendResponse getTransactionTrend(String adminEmail, String period) {
        LocalDateTime start;
        LocalDateTime baseDateTime;
        this.validateAdmin(adminEmail);
        String normalizedPeriod = this.normalizePeriod(period);
        LocalDateTime end = baseDateTime = this.resolveTrendBaseDateTime();
        ArrayList<String> labels = new ArrayList<String>();
        LinkedHashMap<Object, Long> amountByLabel = new LinkedHashMap<Object, Long>();
        if ("weekly".equals(normalizedPeriod)) {
            start = baseDateTime.toLocalDate().minusDays(6L).atStartOfDay();
            for (int i = 0; i < 7; ++i) {
                LocalDate day = start.toLocalDate().plusDays(i);
                String label2 = day.getMonthValue() + "/" + day.getDayOfMonth();
                labels.add(label2);
                amountByLabel.put(label2, 0L);
            }
        } else if ("monthly".equals(normalizedPeriod)) {
            LocalDate monthStart = baseDateTime.toLocalDate().withDayOfMonth(1);
            start = monthStart.atStartOfDay();
            int daysInMonth = monthStart.lengthOfMonth();
            for (int day = 1; day <= daysInMonth; ++day) {
                String label3 = day + "\uc77c";
                labels.add(label3);
                amountByLabel.put(label3, 0L);
            }
        } else {
            LocalDate yearStart = baseDateTime.toLocalDate().withDayOfYear(1);
            start = yearStart.atStartOfDay();
            for (int month = 1; month <= 12; ++month) {
                String label4 = month + "\uc6d4";
                labels.add(label4);
                amountByLabel.put(label4, 0L);
            }
        }
        List<StoreOrder> orders = this.storeOrderRepository.findByOrder_OrderedAtBetween(start, end);
        Map<Long, Long> refundByStoreOrderId = this.buildRefundMap(orders);
        for (StoreOrder storeOrder : orders) {
            if (storeOrder.getOrder() == null || storeOrder.getOrder().getOrderedAt() == null) continue;
            LocalDateTime orderedAt = storeOrder.getOrder().getOrderedAt();
            String key = "weekly".equals(normalizedPeriod) ? orderedAt.getMonthValue() + "/" + orderedAt.getDayOfMonth() : ("monthly".equals(normalizedPeriod) ? orderedAt.getDayOfMonth() + "\uc77c" : orderedAt.getMonthValue() + "\uc6d4");
            long grossAmount = storeOrder.getFinalPrice() == null ? 0L : (long)storeOrder.getFinalPrice().intValue();
            long refundAmount = refundByStoreOrderId.getOrDefault(storeOrder.getId(), 0L);
            long netAmount = Math.max(0L, grossAmount - refundAmount);
            amountByLabel.computeIfPresent(key, (k, v) -> v + netAmount);
        }
        List<Long> yValues = labels.stream().map(label -> amountByLabel.getOrDefault(label, 0L)).toList();
        long maxY = yValues.stream().mapToLong(Long::longValue).max().orElse(0L);
        return AdminTransactionTrendResponse.builder().period(normalizedPeriod).xLabels(labels).yValues(yValues).maxY(maxY).build();
    }

    public AdminPaymentSummaryResponse getPaymentSummary(String adminEmail, String yearMonth) {
        this.validateAdmin(adminEmail);
        DateRange range = this.resolveMonthRange(yearMonth);
        SummaryData summaryData = this.buildPaymentSummary(range.startDateTime(), range.endExclusiveDateTime());
        long regularSalesAmount = this.storeOrderRepository.sumFinalPriceByOrderTypeAndOrderedAtBetween(OrderType.REGULAR, range.startDateTime(), range.endExclusiveDateTime());
        long subscriptionSalesAmount = this.storeOrderRepository.sumFinalPriceByOrderTypeAndOrderedAtBetween(OrderType.SUBSCRIPTION, range.startDateTime(), range.endExclusiveDateTime());
        return AdminPaymentSummaryResponse.builder().grossPaymentAmount(summaryData.totalAmount()).platformFeeRevenue(summaryData.totalCommission()).refundAmount(summaryData.totalRefundAmount()).netRevenue(summaryData.netRevenue()).paymentCount(summaryData.paymentCount()).refundRequestedCount(this.paymentRefundRepository.countByRefundStatus(RefundStatus.REQUESTED)).refundApprovedCount(this.paymentRefundRepository.countByRefundStatus(RefundStatus.APPROVED)).refundRejectedCount(this.paymentRefundRepository.countByRefundStatus(RefundStatus.REJECTED)).refundRequestedAmount(this.paymentRefundRepository.sumRefundAmountByRefundStatusAndRefundedAtBetween(RefundStatus.REQUESTED, range.startDateTime(), range.endExclusiveDateTime())).refundApprovedAmount(this.paymentRefundRepository.sumRefundAmountByRefundStatusAndRefundedAtBetween(RefundStatus.APPROVED, range.startDateTime(), range.endExclusiveDateTime())).refundRejectedAmount(this.paymentRefundRepository.sumRefundAmountByRefundStatusAndRefundedAtBetween(RefundStatus.REJECTED, range.startDateTime(), range.endExclusiveDateTime())).regularSalesAmount(regularSalesAmount).subscriptionSalesAmount(subscriptionSalesAmount).build();
    }

    public AdminPaymentListResponse getPayments(String adminEmail, String yearMonth, String keyword, Pageable pageable) {
        this.validateAdmin(adminEmail);
        DateRange range = this.resolveMonthRange(yearMonth);
        String normalizedKeyword = this.normalizeKeyword(keyword);
        Page<StoreOrder> storeOrderPage = this.storeOrderRepository.searchForAdminPayments(range.startDateTime(), range.endExclusiveDateTime(), normalizedKeyword, pageable);
        List<Long> orderIds = storeOrderPage.getContent().stream().map(storeOrder -> storeOrder.getOrder().getId()).distinct().toList();
        List<Long> storeOrderIds = storeOrderPage.getContent().stream().map(StoreOrder::getId).toList();
        Map<Long, Payment> paymentByOrderId = this.paymentRepository.findByOrder_IdIn(orderIds).stream().collect(Collectors.toMap(payment -> payment.getOrder().getId(), payment -> payment, (left, right) -> left));
        HashMap<Long, Long> refundByStoreOrderId = new HashMap<Long, Long>();
        if (!storeOrderIds.isEmpty()) {
            for (Object[] row : this.paymentRefundRepository.sumRefundAmountGroupByStoreOrderId(storeOrderIds)) {
                Long storeOrderId = ((Number)row[0]).longValue();
                Long refundAmount = ((Number)row[1]).longValue();
                refundByStoreOrderId.put(storeOrderId, refundAmount);
            }
        }
        ArrayList<AdminPaymentListResponse.Item> content = new ArrayList<AdminPaymentListResponse.Item>();
        for (StoreOrder storeOrder2 : storeOrderPage.getContent()) {
            Payment payment2 = paymentByOrderId.get(storeOrder2.getOrder().getId());
            long amount = storeOrder2.getFinalPrice() == null ? 0L : (long)storeOrder2.getFinalPrice().intValue();
            long refundAmount = refundByStoreOrderId.getOrDefault(storeOrder2.getId(), 0L);
            long effectiveAmount = Math.max(0L, amount - refundAmount);
            long commission = Math.round((double)effectiveAmount * 0.05);
            content.add(AdminPaymentListResponse.Item.builder().storeOrderId(storeOrder2.getId()).orderNumber(storeOrder2.getOrder().getOrderNumber()).mart(storeOrder2.getStore().getStoreName()).category(storeOrder2.getStore().getStoreCategory() != null ? storeOrder2.getStore().getStoreCategory().getCategoryName() : "\ubbf8\ubd84\ub958").region(this.extractRegion(storeOrder2.getStore())).customerName(storeOrder2.getOrder().getUser().getName()).amount(amount).commission(commission).refundAmount(refundAmount).status(this.toPaymentStatusLabel(payment2)).paymentStatus(payment2 != null && payment2.getPaymentStatus() != null ? payment2.getPaymentStatus().name() : null).paymentMethod(payment2 != null && payment2.getPaymentMethod() != null ? payment2.getPaymentMethod().name() : null).paidAt(payment2 != null ? payment2.getPaidAt() : null).orderedAt(storeOrder2.getOrder().getOrderedAt()).build());
        }
        SummaryData summaryData = this.buildPaymentSummary(range.startDateTime(), range.endExclusiveDateTime());
        return AdminPaymentListResponse.builder().content(content).stats(AdminPaymentListResponse.Stats.builder().totalAmount(summaryData.totalAmount()).totalCommission(summaryData.totalCommission()).totalRefundAmount(summaryData.totalRefundAmount()).netRevenue(summaryData.netRevenue()).build()).page(AdminPaymentListResponse.PageInfo.builder().page(storeOrderPage.getNumber()).size(storeOrderPage.getSize()).totalElements(storeOrderPage.getTotalElements()).totalPages(storeOrderPage.getTotalPages()).hasNext(storeOrderPage.hasNext()).build()).build();
    }

    public AdminStoreSettlementSummaryResponse getStoreSettlementSummary(String adminEmail, String yearMonth) {
        this.validateAdmin(adminEmail);
        DateRange range = this.resolveMonthRange(yearMonth);
        List<Settlement> settlements = this.settlementRepository.findByTargetTypeAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.STORE, range.startDate(), range.endDate());
        long totalTargets = settlements.size();
        long completedTargets = settlements.stream().filter(item -> item.getStatus() == SettlementStatus.COMPLETED).count();
        long pendingTargets = settlements.stream().filter(item -> item.getStatus() == SettlementStatus.PENDING).count();
        long failedTargets = settlements.stream().filter(item -> item.getStatus() == SettlementStatus.FAILED).count();
        long totalSettlementAmount = settlements.stream().map(Settlement::getSettlementAmount).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
        double completedRate = totalTargets == 0L ? 0.0 : (double)completedTargets * 100.0 / (double)totalTargets;
        return AdminStoreSettlementSummaryResponse.builder().totalTargets(totalTargets).completedTargets(completedTargets).pendingTargets(pendingTargets).failedTargets(failedTargets).totalSettlementAmount(totalSettlementAmount).completedRate((double)Math.round(completedRate * 10.0) / 10.0).build();
    }

    public AdminStoreSettlementTrendResponse getStoreSettlementTrend(String adminEmail, Integer months, String yearMonth) {
        this.validateAdmin(adminEmail);
        int targetMonths = months == null || months < 2 || months > 12 ? 6 : months;
        YearMonth endMonth = yearMonth == null || yearMonth.isBlank() ? YearMonth.now() : this.parseYearMonth(yearMonth);
        YearMonth startMonth = endMonth.minusMonths((long)targetMonths - 1L);
        ArrayList<String> labels = new ArrayList<String>();
        LinkedHashMap<YearMonth, Long> amountMap = new LinkedHashMap<YearMonth, Long>();
        for (int i = 0; i < targetMonths; ++i) {
            YearMonth month = startMonth.plusMonths(i);
            labels.add(month.getMonthValue() + "\uc6d4");
            amountMap.put(month, 0L);
        }
        List<Settlement> settlements = this.settlementRepository.findByTargetTypeAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.STORE, startMonth.atDay(1), endMonth.atEndOfMonth());
        for (Settlement settlement : settlements) {
            YearMonth month = YearMonth.from(settlement.getSettlementPeriodStart());
            if (!amountMap.containsKey(month)) continue;
            long amount = settlement.getSettlementAmount() == null ? 0L : (long)settlement.getSettlementAmount().intValue();
            amountMap.compute(month, (k, v) -> v == null ? amount : v + amount);
        }
        List<Long> yValues = amountMap.values().stream().toList();
        long totalAmount = yValues.stream().mapToLong(Long::longValue).sum();
        long first = yValues.isEmpty() ? 0L : (Long)yValues.get(0);
        long last = yValues.isEmpty() ? 0L : (Long)yValues.get(yValues.size() - 1);
        double changeRate = first == 0L ? 0.0 : (double)(last - first) / (double)first * 100.0;
        return AdminStoreSettlementTrendResponse.builder().xLabels(labels).yValues(yValues).totalAmount(totalAmount).changeRate((double)Math.round(changeRate * 10.0) / 10.0).build();
    }

    public AdminStoreSettlementListResponse getStoreSettlements(String adminEmail, String yearMonth, SettlementStatus status, String keyword, Pageable pageable) {
        Page<Settlement> settlementPage;
        List<Settlement> settlementsForStats;
        this.validateAdmin(adminEmail);
        DateRange range = this.resolveMonthRange(yearMonth);
        String normalizedKeyword = this.normalizeKeyword(keyword);
        List<Settlement> list = settlementsForStats = status == null ? this.settlementRepository.findByTargetTypeAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.STORE, range.startDate(), range.endDate()) : this.settlementRepository.findByTargetTypeAndStatusAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.STORE, status, range.startDate(), range.endDate());
        if (normalizedKeyword.isBlank()) {
            settlementPage = status == null ? this.settlementRepository.findByTargetTypeAndSettlementPeriodStartBetween(SettlementTargetType.STORE, range.startDate(), range.endDate(), pageable) : this.settlementRepository.findByTargetTypeAndStatusAndSettlementPeriodStartBetween(SettlementTargetType.STORE, status, range.startDate(), range.endDate(), pageable);
        } else {
            List<Settlement> baseList = status == null ? this.settlementRepository.findByTargetTypeAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.STORE, range.startDate(), range.endDate()) : this.settlementRepository.findByTargetTypeAndStatusAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.STORE, status, range.startDate(), range.endDate());
            Map<Long, Store> storeMap = this.buildStoreMap(baseList);
            List<Settlement> filtered = baseList.stream().filter(settlement -> {
                Store store = (Store)storeMap.get(settlement.getTargetId());
                if (store == null) {
                    return false;
                }
                String storeName = store.getStoreName() == null ? "" : store.getStoreName();
                String storeIdCode = this.toStoreIdCode(store.getId());
                return storeName.toLowerCase(Locale.ROOT).contains(normalizedKeyword) || storeIdCode.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
            }).toList();
            settlementPage = this.toPage(filtered, pageable);
        }
        Map<Long, Store> storeMap = this.buildStoreMap(settlementPage.getContent());
        List<AdminStoreSettlementListResponse.Item> items = settlementPage.getContent().stream().map(settlement -> {
            Store store = (Store)storeMap.get(settlement.getTargetId());
            return AdminStoreSettlementListResponse.Item.builder().settlementId(settlement.getId()).storeId(store != null ? store.getId() : settlement.getTargetId()).storeName(store != null ? store.getStoreName() : "\uc54c\uc218\uc5c6\ub294 \ub9c8\ud2b8").idCode(this.toStoreIdCode(settlement.getTargetId())).region(store != null ? this.extractRegion(store) : "\ubbf8\uc0c1").amount(settlement.getSettlementAmount() != null ? (long)settlement.getSettlementAmount().intValue() : 0L).settlementPeriodStart(settlement.getSettlementPeriodStart()).settlementPeriodEnd(settlement.getSettlementPeriodEnd()).settledAt(settlement.getSettledAt()).status(settlement.getStatus()).statusLabel(this.toSettlementStatusLabel(settlement.getStatus())).build();
        }).toList();
        long completed = settlementsForStats.stream().filter(item -> item.getStatus() == SettlementStatus.COMPLETED).count();
        long pending = settlementsForStats.stream().filter(item -> item.getStatus() == SettlementStatus.PENDING).count();
        long failed = settlementsForStats.stream().filter(item -> item.getStatus() == SettlementStatus.FAILED).count();
        return AdminStoreSettlementListResponse.builder().content(items).stats(AdminStoreSettlementListResponse.Stats.builder().total(settlementsForStats.size()).completed(completed).pending(pending).failed(failed).build()).page(AdminStoreSettlementListResponse.PageInfo.builder().page(settlementPage.getNumber()).size(settlementPage.getSize()).totalElements(settlementPage.getTotalElements()).totalPages(settlementPage.getTotalPages()).hasNext(settlementPage.hasNext()).build()).build();
    }

    public AdminTransactionDetailResponse getTransactionDetail(String adminEmail, String period, String label) {
        LocalDateTime bucketEnd;
        LocalDateTime bucketStart;
        this.validateAdmin(adminEmail);
        String normalizedPeriod = this.normalizePeriod(period);
        LocalDateTime baseDateTime = this.resolveTrendBaseDateTime();
        if ("weekly".equals(normalizedPeriod)) {
            LocalDate weeklyStart = baseDateTime.toLocalDate().minusDays(6L);
            bucketStart = null;
            for (int i = 0; i < 7; ++i) {
                LocalDate day = weeklyStart.plusDays(i);
                String dayLabel = day.getMonthValue() + "/" + day.getDayOfMonth();
                if (!dayLabel.equals(label)) continue;
                bucketStart = day.atStartOfDay();
                break;
            }
            if (bucketStart == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "\uc720\ud6a8\ud558\uc9c0 \uc54a\uc740 \ub0a0\uc9dc \ub77c\ubca8\uc785\ub2c8\ub2e4.");
            }
            bucketEnd = bucketStart.plusDays(1L);
        } else if ("monthly".equals(normalizedPeriod)) {
            LocalDate monthStart = baseDateTime.toLocalDate().withDayOfMonth(1);
            String numeric = label.replaceAll("[^0-9]", "");
            if (numeric.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "\uc720\ud6a8\ud558\uc9c0 \uc54a\uc740 \uc77c\uc790 \ub77c\ubca8\uc785\ub2c8\ub2e4.");
            }
            int day = Integer.parseInt(numeric);
            if (day < 1 || day > monthStart.lengthOfMonth()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "\uc720\ud6a8\ud558\uc9c0 \uc54a\uc740 \uc77c\uc790 \ub77c\ubca8\uc785\ub2c8\ub2e4.");
            }
            bucketStart = monthStart.withDayOfMonth(day).atStartOfDay();
            bucketEnd = bucketStart.plusDays(1L);
        } else {
            String numeric = label.replaceAll("[^0-9]", "");
            if (numeric.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "\uc720\ud6a8\ud558\uc9c0 \uc54a\uc740 \uc6d4 \ub77c\ubca8\uc785\ub2c8\ub2e4.");
            }
            int month = Integer.parseInt(numeric);
            if (month < 1 || month > 12) {
                throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "\uc720\ud6a8\ud558\uc9c0 \uc54a\uc740 \uc6d4 \ub77c\ubca8\uc785\ub2c8\ub2e4.");
            }
            LocalDate monthDate = LocalDate.of(baseDateTime.getYear(), month, 1);
            bucketStart = monthDate.atStartOfDay();
            bucketEnd = monthDate.plusMonths(1L).atStartOfDay();
        }
        List<StoreOrder> orders = this.storeOrderRepository.findByOrder_OrderedAtBetween(bucketStart, bucketEnd).stream().filter(so -> so.getOrder() != null && so.getOrder().getOrderedAt() != null).sorted((a, b) -> b.getOrder().getOrderedAt().compareTo(a.getOrder().getOrderedAt())).toList();
        Map<Long, Long> refundByStoreOrderId = this.buildRefundMap(orders);
        List<AdminTransactionDetailResponse.Item> content = orders.stream().map(storeOrder -> AdminTransactionDetailResponse.Item.builder().storeOrderId(storeOrder.getId()).orderNumber(storeOrder.getOrder() != null ? storeOrder.getOrder().getOrderNumber() : "-").storeName(storeOrder.getStore() != null ? storeOrder.getStore().getStoreName() : "-").customerName(storeOrder.getOrder() != null && storeOrder.getOrder().getUser() != null ? storeOrder.getOrder().getUser().getName() : "-").amount(Math.max(0L, (long)(storeOrder.getFinalPrice() == null ? 0 : storeOrder.getFinalPrice()) - refundByStoreOrderId.getOrDefault(storeOrder.getId(), 0L))).orderStatus(storeOrder.getStatus() != null ? storeOrder.getStatus().name() : "-").orderedAt(storeOrder.getOrder() != null ? storeOrder.getOrder().getOrderedAt() : null).build()).toList();
        long totalAmount = content.stream().mapToLong(AdminTransactionDetailResponse.Item::getAmount).sum();
        return AdminTransactionDetailResponse.builder().period(normalizedPeriod).label(label).rangeStart(bucketStart).rangeEnd(bucketEnd.minusNanos(1L)).totalCount(content.size()).totalAmount(totalAmount).content(content).build();
    }

    public AdminTransactionOrderDetailResponse getTransactionOrderDetail(String adminEmail, Long storeOrderId) {
        this.validateAdmin(adminEmail);
        StoreOrder storeOrder = this.storeOrderRepository.findById(storeOrderId).orElse(null);
        if (storeOrder == null) {
            List<StoreOrder> byOrderId = this.storeOrderRepository.findAllByOrderId(storeOrderId);
            storeOrder = byOrderId.isEmpty() ? null : byOrderId.get(0);
        }
        if (storeOrder == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "\uc8fc\ubb38 \uc0c1\uc138 \uc815\ubcf4\ub97c \ucc3e\uc744 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4.");
        }
        Long resolvedStoreOrderId = storeOrder.getId();
        List<OrderProduct> products = this.orderProductRepository.findAllByStoreOrderId(resolvedStoreOrderId);
        List<AdminTransactionOrderDetailResponse.ProductItem> productItems = products.stream().map(item -> AdminTransactionOrderDetailResponse.ProductItem.builder().name(item.getProductNameSnapshot()).unitPrice(item.getPriceSnapshot()).quantity(item.getQuantity()).build()).toList();
        if (productItems.isEmpty()) {
            Long orderId = storeOrder.getOrder().getId();
            Long storeId = storeOrder.getStore().getId();
            productItems = this.orderLineRepository.findAllByOrderId(orderId).stream().filter(line -> Objects.equals(line.getStoreId(), storeId)).map(line -> AdminTransactionOrderDetailResponse.ProductItem.builder().name(line.getProductNameSnapshot()).unitPrice(line.getPriceSnapshot()).quantity(line.getQuantity()).build()).toList();
        }
        Delivery delivery = this.deliveryRepository.findByStoreOrderId(resolvedStoreOrderId).orElse(null);
        Payment payment = this.paymentRepository.findByOrder_Id(storeOrder.getOrder().getId()).orElse(null);
        List<PaymentRefund> refunds = this.paymentRefundRepository.findByStoreOrderIdOrderByCreatedAtDesc(resolvedStoreOrderId);
        long refundAmount = refunds.stream().map(PaymentRefund::getRefundAmount).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
        String refundStatus = refunds.isEmpty() ? "NONE" : refunds.get(0).getRefundStatus().name();
        String deliveryLocation = "-";
        if (delivery != null && delivery.getCustomerLocation() != null) {
            deliveryLocation = String.format(Locale.KOREA, "%.5f, %.5f", delivery.getCustomerLocation().getY(), delivery.getCustomerLocation().getX());
        }
        String deliveryAddress = storeOrder.getOrder().getDeliveryAddress();
        if (deliveryAddress == null || deliveryAddress.isBlank()) {
            deliveryAddress = "주소 정보 없음";
        } else if (deliveryAddress.contains("?")) {
            deliveryAddress = "주소 인코딩 문제로 표시 불가";
        }
        String paymentStatus = payment != null && payment.getPaymentStatus() != null ? payment.getPaymentStatus().name() : "PENDING";
        return AdminTransactionOrderDetailResponse.builder().storeOrderId(resolvedStoreOrderId).orderNumber(storeOrder.getOrder().getOrderNumber()).storeName(storeOrder.getStore() != null ? storeOrder.getStore().getStoreName() : "-").customerName(storeOrder.getOrder().getUser() != null ? storeOrder.getOrder().getUser().getName() : "-").riderName(delivery != null && delivery.getRider() != null ? delivery.getRider().getDisplayName() : "-").riderPhone(delivery != null && delivery.getRider() != null ? delivery.getRider().getDisplayPhone() : "-").deliveryFee(storeOrder.getDeliveryFee()).deliveryAddress(deliveryAddress).deliveryLocation(deliveryLocation).orderedAt(storeOrder.getOrder().getOrderedAt()).paymentStatus(paymentStatus).refundStatus(refundStatus).refundAmount(refundAmount).products(productItems).build();
    }

    public AdminRiderSettlementSummaryResponse getRiderSettlementSummary(String adminEmail, String yearMonth) {
        this.validateAdmin(adminEmail);
        DateRange range = this.resolveMonthRange(yearMonth);
        List<Settlement> settlements = this.settlementRepository.findByTargetTypeAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.RIDER, range.startDate(), range.endDate());
        long totalTargets = settlements.size();
        long completedTargets = settlements.stream().filter(item -> item.getStatus() == SettlementStatus.COMPLETED).count();
        long pendingTargets = settlements.stream().filter(item -> item.getStatus() == SettlementStatus.PENDING).count();
        long failedTargets = settlements.stream().filter(item -> item.getStatus() == SettlementStatus.FAILED).count();
        long totalSettlementAmount = settlements.stream().map(Settlement::getSettlementAmount).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
        double completedRate = totalTargets == 0L ? 0.0 : (double)completedTargets * 100.0 / (double)totalTargets;
        return AdminRiderSettlementSummaryResponse.builder().totalTargets(totalTargets).completedTargets(completedTargets).pendingTargets(pendingTargets).failedTargets(failedTargets).totalSettlementAmount(totalSettlementAmount).completedRate((double)Math.round(completedRate * 10.0) / 10.0).build();
    }

    public AdminRiderSettlementTrendResponse getRiderSettlementTrend(String adminEmail, Integer months, String yearMonth) {
        this.validateAdmin(adminEmail);
        int targetMonths = months == null || months < 2 || months > 12 ? 6 : months;
        YearMonth endMonth = yearMonth == null || yearMonth.isBlank() ? YearMonth.now() : this.parseYearMonth(yearMonth);
        YearMonth startMonth = endMonth.minusMonths((long)targetMonths - 1L);
        ArrayList<String> labels = new ArrayList<String>();
        LinkedHashMap<YearMonth, Long> amountMap = new LinkedHashMap<YearMonth, Long>();
        for (int i = 0; i < targetMonths; ++i) {
            YearMonth month = startMonth.plusMonths(i);
            labels.add(month.getMonthValue() + "\uc6d4");
            amountMap.put(month, 0L);
        }
        List<Settlement> settlements = this.settlementRepository.findByTargetTypeAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.RIDER, startMonth.atDay(1), endMonth.atEndOfMonth());
        for (Settlement settlement : settlements) {
            YearMonth month = YearMonth.from(settlement.getSettlementPeriodStart());
            if (!amountMap.containsKey(month)) continue;
            long amount = settlement.getSettlementAmount() == null ? 0L : (long)settlement.getSettlementAmount().intValue();
            amountMap.compute(month, (k, v) -> v == null ? amount : v + amount);
        }
        List<Long> yValues = amountMap.values().stream().toList();
        long totalAmount = yValues.stream().mapToLong(Long::longValue).sum();
        long first = yValues.isEmpty() ? 0L : (Long)yValues.get(0);
        long last = yValues.isEmpty() ? 0L : (Long)yValues.get(yValues.size() - 1);
        double changeRate = first == 0L ? 0.0 : (double)(last - first) / (double)first * 100.0;
        return AdminRiderSettlementTrendResponse.builder().xLabels(labels).yValues(yValues).totalAmount(totalAmount).changeRate((double)Math.round(changeRate * 10.0) / 10.0).build();
    }

    public AdminRiderSettlementListResponse getRiderSettlements(String adminEmail, String yearMonth, SettlementStatus status, String keyword, Pageable pageable) {
        Page<Settlement> settlementPage;
        List<Settlement> settlementsForStats;
        this.validateAdmin(adminEmail);
        DateRange range = this.resolveMonthRange(yearMonth);
        String normalizedKeyword = this.normalizeKeyword(keyword);
        List<Settlement> list = settlementsForStats = status == null ? this.settlementRepository.findByTargetTypeAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.RIDER, range.startDate(), range.endDate()) : this.settlementRepository.findByTargetTypeAndStatusAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.RIDER, status, range.startDate(), range.endDate());
        if (normalizedKeyword.isBlank()) {
            settlementPage = status == null ? this.settlementRepository.findByTargetTypeAndSettlementPeriodStartBetween(SettlementTargetType.RIDER, range.startDate(), range.endDate(), pageable) : this.settlementRepository.findByTargetTypeAndStatusAndSettlementPeriodStartBetween(SettlementTargetType.RIDER, status, range.startDate(), range.endDate(), pageable);
        } else {
            List<Settlement> baseList = status == null ? this.settlementRepository.findByTargetTypeAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.RIDER, range.startDate(), range.endDate()) : this.settlementRepository.findByTargetTypeAndStatusAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.RIDER, status, range.startDate(), range.endDate());
            Map<Long, Rider> riderMap = this.buildRiderMap(baseList);
            List<Settlement> filtered = baseList.stream().filter(settlement -> {
                Rider rider = (Rider)riderMap.get(settlement.getTargetId());
                if (rider == null) {
                    return false;
                }
                String riderName = rider.getDisplayName() == null ? "" : rider.getDisplayName();
                String riderIdCode = this.toRiderIdCode(rider.getId());
                return riderName.toLowerCase(Locale.ROOT).contains(normalizedKeyword) || riderIdCode.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
            }).toList();
            settlementPage = this.toPage(filtered, pageable);
        }
        Map<Long, Rider> riderMap = this.buildRiderMap(settlementPage.getContent());
        List<AdminRiderSettlementListResponse.Item> items = settlementPage.getContent().stream().map(settlement -> {
            Rider rider = (Rider)riderMap.get(settlement.getTargetId());
            return AdminRiderSettlementListResponse.Item.builder().settlementId(settlement.getId()).riderId(rider != null ? rider.getId() : settlement.getTargetId()).riderName(rider != null ? rider.getDisplayName() : "\uc54c\uc218\uc5c6\ub294 \ub77c\uc774\ub354").riderPhone(rider != null ? rider.getDisplayPhone() : "-").idCode(this.toRiderIdCode(settlement.getTargetId())).region("\uc804\uad6d").amount(settlement.getSettlementAmount() != null ? (long)settlement.getSettlementAmount().intValue() : 0L).settlementPeriodStart(settlement.getSettlementPeriodStart()).settlementPeriodEnd(settlement.getSettlementPeriodEnd()).settledAt(settlement.getSettledAt()).status(settlement.getStatus()).statusLabel(this.toSettlementStatusLabel(settlement.getStatus())).build();
        }).toList();
        long completed = settlementsForStats.stream().filter(item -> item.getStatus() == SettlementStatus.COMPLETED).count();
        long pending = settlementsForStats.stream().filter(item -> item.getStatus() == SettlementStatus.PENDING).count();
        long failed = settlementsForStats.stream().filter(item -> item.getStatus() == SettlementStatus.FAILED).count();
        return AdminRiderSettlementListResponse.builder().content(items).stats(AdminRiderSettlementListResponse.Stats.builder().total(settlementsForStats.size()).completed(completed).pending(pending).failed(failed).build()).page(AdminRiderSettlementListResponse.PageInfo.builder().page(settlementPage.getNumber()).size(settlementPage.getSize()).totalElements(settlementPage.getTotalElements()).totalPages(settlementPage.getTotalPages()).hasNext(settlementPage.hasNext()).build()).build();
    }

    @Transactional
    public AdminStoreSettlementExecuteResponse executeStoreSettlement(String adminEmail, AdminStoreSettlementExecuteRequest request) {
        this.validateAdmin(adminEmail);
        YearMonth target = this.parseYearMonth(request.getYearMonth());
        int completedCount = this.settlementBatchLauncher.runMonthlyPipeline(target);
        return AdminStoreSettlementExecuteResponse.builder().yearMonth(target.toString()).completedCount(completedCount).build();
    }

    @Transactional
    public AdminStoreSettlementExecuteResponse executeStoreSettlementSingle(String adminEmail, Long settlementId) {
        this.validateAdmin(adminEmail);
        Settlement settlement = (Settlement)this.settlementRepository.findById(settlementId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "\uc815\uc0b0 \uc815\ubcf4\ub97c \ucc3e\uc744 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4."));
        if (settlement.getTargetType() != SettlementTargetType.STORE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "\ub9c8\ud2b8 \uc815\uc0b0 ID\uac00 \uc544\ub2d9\ub2c8\ub2e4.");
        }
        if (settlement.getStatus() != SettlementStatus.COMPLETED) {
            settlement.complete(LocalDateTime.now());
        }
        return AdminStoreSettlementExecuteResponse.builder().yearMonth(YearMonth.from(settlement.getSettlementPeriodStart()).toString()).completedCount(1).build();
    }

    @Transactional
    public AdminStoreSettlementExecuteResponse executeRiderSettlement(String adminEmail, AdminStoreSettlementExecuteRequest request) {
        this.validateAdmin(adminEmail);
        YearMonth target = this.parseYearMonth(request.getYearMonth());
        long before = this.settlementRepository.findByTargetTypeAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.RIDER, target.atDay(1), target.atEndOfMonth()).size();
        this.riderSettlementBatchLauncher.runMonthlyPipeline(target);
        long after = this.settlementRepository.findByTargetTypeAndSettlementPeriodStartBetweenOrderBySettlementPeriodStartDesc(SettlementTargetType.RIDER, target.atDay(1), target.atEndOfMonth()).size();
        int completedCount = (int)Math.max(0L, after - before);
        return AdminStoreSettlementExecuteResponse.builder().yearMonth(target.toString()).completedCount(completedCount).build();
    }

    @Transactional
    public AdminStoreSettlementExecuteResponse executeRiderSettlementSingle(String adminEmail, Long settlementId) {
        this.validateAdmin(adminEmail);
        Settlement settlement = (Settlement)this.settlementRepository.findById(settlementId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "\uc815\uc0b0 \uc815\ubcf4\ub97c \ucc3e\uc744 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4."));
        if (settlement.getTargetType() != SettlementTargetType.RIDER) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "\ub77c\uc774\ub354 \uc815\uc0b0 ID\uac00 \uc544\ub2d9\ub2c8\ub2e4.");
        }
        if (settlement.getStatus() != SettlementStatus.COMPLETED) {
            settlement.complete(LocalDateTime.now());
        }
        return AdminStoreSettlementExecuteResponse.builder().yearMonth(YearMonth.from(settlement.getSettlementPeriodStart()).toString()).completedCount(1).build();
    }

    private SummaryData buildPaymentSummary(LocalDateTime start, LocalDateTime endExclusive) {
        long totalAmount = this.storeOrderRepository.sumFinalPriceByOrderOrderedAtBetween(start, endExclusive);
        long totalCommission = Math.round((double)totalAmount * 0.05);
        long totalRefundAmount = this.paymentRepository.sumRefundedAmountByPaidAtBetween(start, endExclusive);
        long netRevenue = Math.max(0L, totalAmount - totalCommission - totalRefundAmount);
        long paymentCount = this.paymentRepository.countByPaymentStatusInAndPaidAtBetween(COUNTABLE_PAYMENT_STATUSES, start, endExclusive);
        return new SummaryData(totalAmount, totalCommission, totalRefundAmount, netRevenue, paymentCount);
    }

    private User validateAdmin(String adminEmail) {
        User admin = this.userRepository.findByEmailAndDeletedAtIsNull(adminEmail).orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_AUTHORITY_REQUIRED));
        if (!admin.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_AUTHORITY_REQUIRED);
        }
        return admin;
    }

    private DateRange resolveMonthRange(String yearMonth) {
        YearMonth targetMonth = yearMonth == null || yearMonth.isBlank() ? YearMonth.now() : this.parseYearMonth(yearMonth);
        LocalDate start = targetMonth.atDay(1);
        LocalDate end = targetMonth.atEndOfMonth();
        LocalDateTime startDateTime = start.atStartOfDay();
        LocalDateTime endExclusive = targetMonth.plusMonths(1L).atDay(1).atStartOfDay();
        return new DateRange(start, end, startDateTime, endExclusive);
    }

    private YearMonth parseYearMonth(String yearMonth) {
        try {
            return YearMonth.parse(yearMonth);
        }
        catch (Exception exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "yearMonth\ub294 yyyy-MM \ud615\uc2dd\uc774\uc5b4\uc57c \ud569\ub2c8\ub2e4.");
        }
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    }

    private LocalDateTime resolveTrendBaseDateTime() {
        LocalDateTime maxOrderedAt = this.storeOrderRepository.findMaxOrderOrderedAt();
        return maxOrderedAt != null ? maxOrderedAt : LocalDateTime.now();
    }

    private String normalizePeriod(String period) {
        if (period == null || period.isBlank()) {
            return "weekly";
        }
        String normalized = period.trim().toLowerCase(Locale.ROOT);
        if ("weekly".equals(normalized) || "monthly".equals(normalized) || "yearly".equals(normalized)) {
            return normalized;
        }
        return "weekly";
    }

    private String extractRegion(Store store) {
        if (store.getAddress() == null || store.getAddress().getAddressLine1() == null) {
            return "\ubbf8\uc0c1";
        }
        String address = store.getAddress().getAddressLine1().trim();
        if (address.isBlank()) {
            return "\ubbf8\uc0c1";
        }
        String[] tokens = address.split("\\s+");
        return tokens.length > 0 ? tokens[0] : "\ubbf8\uc0c1";
    }

    private String toPaymentStatusLabel(Payment payment) {
        if (payment == null || payment.getPaymentStatus() == null) {
            return "\ud655\uc778 \ub300\uae30";
        }
        return switch (payment.getPaymentStatus()) {
            default -> throw new MatchException(null, null);
            case PaymentStatus.APPROVED, PaymentStatus.PARTIAL_REFUNDED -> "\uc9c0\uae09 \ucc98\ub9ac\uc911";
            case PaymentStatus.REFUNDED, PaymentStatus.CANCELLED -> "\ud658\ubd88 \uc644\ub8cc";
            case PaymentStatus.REFUND_REQUESTED -> "\ud658\ubd88 \uc694\uccad";
            case PaymentStatus.FAILED -> "\uacb0\uc81c \uc2e4\ud328";
            case PaymentStatus.READY, PaymentStatus.PENDING -> "\ud655\uc778 \ub300\uae30";
            case PaymentStatus.REVERSAL_PENDING -> "\ucde8\uc18c \ucc98\ub9ac\uc911";
            case PaymentStatus.RECONCILIATION_REQUIRED -> "\ud655\uc778 \ud544\uc694";
        };
    }

    private String toSettlementStatusLabel(SettlementStatus status) {
        if (status == null) {
            return "\ud655\uc778 \ub300\uae30";
        }
        return switch (status) {
            default -> throw new MatchException(null, null);
            case SettlementStatus.COMPLETED -> "\uc9c0\uae09 \uc644\ub8cc";
            case SettlementStatus.PENDING -> "\uc9c0\uae09 \ucc98\ub9ac\uc911";
            case SettlementStatus.FAILED -> "\uc9c0\uae09 \uc2e4\ud328";
        };
    }

    private String toStoreIdCode(Long storeId) {
        return "STORE-" + storeId;
    }

    private String toRiderIdCode(Long riderId) {
        return "RIDER-" + riderId;
    }

    private Map<Long, Long> buildRefundMap(List<StoreOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return Map.of();
        }
        List<Long> storeOrderIds = orders.stream().map(StoreOrder::getId).toList();
        HashMap<Long, Long> refundByStoreOrderId = new HashMap<Long, Long>();
        for (Object[] row : this.paymentRefundRepository.sumRefundAmountGroupByStoreOrderId(storeOrderIds)) {
            Long storeOrderId = ((Number)row[0]).longValue();
            Long refundAmount = ((Number)row[1]).longValue();
            refundByStoreOrderId.put(storeOrderId, refundAmount);
        }
        return refundByStoreOrderId;
    }

    private Map<Long, Store> buildStoreMap(Collection<Settlement> settlements) {
        List<Long> storeIds = settlements.stream().map(Settlement::getTargetId).distinct().toList();
        HashMap<Long, Store> map = new HashMap<Long, Store>();
        for (Store store : this.storeRepository.findAllById(storeIds)) {
            map.put(store.getId(), store);
        }
        return map;
    }

    private Map<Long, Rider> buildRiderMap(Collection<Settlement> settlements) {
        List<Long> riderIds = settlements.stream().map(Settlement::getTargetId).distinct().toList();
        HashMap<Long, Rider> map = new HashMap<Long, Rider>();
        for (Rider rider : this.riderRepository.findAllById(riderIds)) {
            map.put(rider.getId(), rider);
        }
        return map;
    }

    private Page<Settlement> toPage(List<Settlement> fullList, Pageable pageable) {
        int pageSize = pageable.getPageSize();
        int pageNumber = pageable.getPageNumber();
        int fromIndex = Math.min(pageNumber * pageSize, fullList.size());
        int toIndex = Math.min(fromIndex + pageSize, fullList.size());
        List<Settlement> content = fullList.subList(fromIndex, toIndex);
        return new PageImpl(content, pageable, (long)fullList.size());
    }

    @Generated
    public AdminFinanceService(UserRepository userRepository, StoreRepository storeRepository, ReportRepository reportRepository, InquiryRepository inquiryRepository, RiderRepository riderRepository, DeliveryRepository deliveryRepository, OrderProductRepository orderProductRepository, OrderLineRepository orderLineRepository, StoreOrderRepository storeOrderRepository, PaymentRepository paymentRepository, PaymentRefundRepository paymentRefundRepository, SettlementRepository settlementRepository, StoreSettlementBatchLauncher settlementBatchLauncher, RiderSettlementBatchLauncher riderSettlementBatchLauncher) {
        this.userRepository = userRepository;
        this.storeRepository = storeRepository;
        this.reportRepository = reportRepository;
        this.inquiryRepository = inquiryRepository;
        this.riderRepository = riderRepository;
        this.deliveryRepository = deliveryRepository;
        this.orderProductRepository = orderProductRepository;
        this.orderLineRepository = orderLineRepository;
        this.storeOrderRepository = storeOrderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentRefundRepository = paymentRefundRepository;
        this.settlementRepository = settlementRepository;
        this.settlementBatchLauncher = settlementBatchLauncher;
        this.riderSettlementBatchLauncher = riderSettlementBatchLauncher;
    }

    private record DateRange(LocalDate startDate, LocalDate endDate, LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime) {
    }

    private record SummaryData(long totalAmount, long totalCommission, long totalRefundAmount, long netRevenue, long paymentCount) {
    }
}
