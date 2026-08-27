package com.example.finalproject.store.repository.custom;

import static com.example.finalproject.product.domain.QProduct.product;
import static com.example.finalproject.store.domain.QStore.store;
import static com.example.finalproject.store.domain.QStoreBusinessHour.storeBusinessHour;

import com.example.finalproject.global.util.GeometryUtil;
import com.example.finalproject.store.dto.response.QStoreNearbyResponse;
import com.example.finalproject.store.dto.response.StoreNearbyResponse;
import com.example.finalproject.store.enums.StoreActiveStatus;
import com.example.finalproject.store.enums.StoreStatus;
import com.example.finalproject.user.dto.request.GetStoreSearchRequest;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberTemplate;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Point;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StoreRepositoryImpl implements StoreRepositoryCustom {
    private static final double SEARCH_RADIUS_METERS = 3000.0;

    private final JPAQueryFactory queryFactory;

    @Override
    public Slice<StoreNearbyResponse> findNearbyStoresByCategory(GetStoreSearchRequest request) {
        Point currentLocation = GeometryUtil.createPoint(request.getLongitude(), request.getLatitude());
        NumberTemplate<Double> distance = Expressions.numberTemplate(
                Double.class, "st_distance_geography({0}, {1})", store.address.location, currentLocation);
        NumberTemplate<Double> latitude = Expressions.numberTemplate(
                Double.class, "ST_Y(ST_GeometryFromText(ST_AsText({0})))", store.address.location);
        NumberTemplate<Double> longitude = Expressions.numberTemplate(
                Double.class, "ST_X(ST_GeometryFromText(ST_AsText({0})))", store.address.location);
        short todayDayOfWeek = (short) (LocalDate.now().getDayOfWeek().getValue() % 7);

        List<StoreNearbyResponse> content = queryFactory.select(new QStoreNearbyResponse(
                        store.id,
                        store.storeName,
                        distance.coalesce(0.0),
                        store.reviewCount.coalesce(0),
                        store.storeImage,
                        store.isActive.eq(StoreActiveStatus.ACTIVE).and(store.isDeliveryAvailable.eq(true)),
                        store.address.addressLine1,
                        store.address.addressLine2,
                        latitude,
                        longitude))
                .from(store)
                .where(
                        within3km(currentLocation),
                        isApprovedAndActive(),
                        notClosedToday(todayDayOfWeek),
                        storeCategoryEq(request.getStoreCategoryId()),
                        productKeywordCondition(request.getKeyword()),
                        cursorCondition(request.getLastDistance(), request.getLastId(), distance))
                .orderBy(distance.asc(), store.id.asc())
                .limit(request.getSize() + 1)
                .fetch();

        return checkLastPage(request.getSize(), content);
    }

    private BooleanExpression within3km(Point currentLocation) {
        return Expressions.booleanTemplate(
                "st_dwithin_geography({0}, {1}, {2})", store.address.location, currentLocation, SEARCH_RADIUS_METERS);
    }

    private BooleanExpression isApprovedAndActive() {
        return store.status.eq(StoreStatus.APPROVED)
                .and(store.isActive.eq(StoreActiveStatus.ACTIVE))
                .and(store.deletedAt.isNull());
    }

    private BooleanExpression notClosedToday(short todayDayOfWeek) {
        return store.id.in(JPAExpressions.select(storeBusinessHour.store.id)
                .from(storeBusinessHour)
                .where(storeBusinessHour.dayOfWeek.eq(todayDayOfWeek), storeBusinessHour.isClosed.eq(false)));
    }

    private BooleanExpression storeCategoryEq(Long storeCategoryId) {
        return storeCategoryId == null ? null : store.storeCategory.id.eq(storeCategoryId);
    }

    private BooleanExpression productKeywordCondition(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return JPAExpressions.selectOne()
                .from(product)
                .where(product.store.id.eq(store.id), product.isActive.isTrue(), product.productName.containsIgnoreCase(keyword))
                .exists();
    }

    private BooleanExpression cursorCondition(Double lastDistance, Long lastId, NumberTemplate<Double> distance) {
        if (lastDistance == null || lastId == null) {
            return null;
        }
        return distance.gt(lastDistance)
                .or(distance.eq(lastDistance).and(store.id.gt(lastId)));
    }

    private Slice<StoreNearbyResponse> checkLastPage(int size, List<StoreNearbyResponse> content) {
        boolean hasNext = content.size() > size;
        if (hasNext) {
            content.remove(size);
        }
        return new SliceImpl<>(content, PageRequest.of(0, size), hasNext);
    }
}
