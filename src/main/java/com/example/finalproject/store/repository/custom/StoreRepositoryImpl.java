package com.example.finalproject.store.repository.custom;

import com.example.finalproject.store.dto.response.StoreNearbyResponse;
import com.example.finalproject.user.dto.request.GetStoreSearchRequest;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StoreRepositoryImpl implements StoreRepositoryCustom {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Slice<StoreNearbyResponse> findNearbyStoresByCategory(GetStoreSearchRequest request) {
        return findNearbyStoresWithSpatialIndex(request);
    }

    private Slice<StoreNearbyResponse> findNearbyStoresWithSpatialIndex(GetStoreSearchRequest request) {
        short todayDayOfWeek = (short) (LocalDate.now().getDayOfWeek().getValue() % 7);
        StringBuilder sql = new StringBuilder("""
                select s.id, s.store_name,
                       ST_Distance(s.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) as distance,
                       coalesce(s.review_count, 0) as review_count, s.store_image,
                       s.is_active = 'ACTIVE' and s.is_delivery_available = true as is_open,
                       s.address_line1, s.address_line2,
                       ST_Y(s.location::geometry) as latitude, ST_X(s.location::geometry) as longitude
                from stores s
                where ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 3000)
                  and s.status = 'APPROVED'
                  and s.is_active = 'ACTIVE'
                  and s.deleted_at is null
                  and exists (
                      select 1 from store_business_hours bh
                      where bh.store_id = s.id and bh.day_of_week = ? and bh.is_closed = false
                  )
                """);
        List<Object> arguments = new java.util.ArrayList<>(List.of(
                request.getLongitude(), request.getLatitude(), request.getLongitude(), request.getLatitude(), todayDayOfWeek));
        if (request.getStoreCategoryId() != null) {
            sql.append(" and s.store_category_id = ?");
            arguments.add(request.getStoreCategoryId());
        }
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            sql.append("""
                     and exists (
                         select 1 from products p
                         where p.store_id = s.id and p.is_active = true
                           and lower(p.product_name) like '%' || lower(?) || '%'
                           escape '!'
                     )
                    """);
            arguments.add(escapeLikeKeyword(request.getKeyword()));
        }
        if (request.getLastDistance() != null && request.getLastId() != null) {
            sql.append("""
                     and (ST_Distance(s.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) > ?
                          or (ST_Distance(s.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) = ? and s.id > ?))
                    """);
            arguments.add(request.getLongitude());
            arguments.add(request.getLatitude());
            arguments.add(request.getLastDistance());
            arguments.add(request.getLongitude());
            arguments.add(request.getLatitude());
            arguments.add(request.getLastDistance());
            arguments.add(request.getLastId());
        }
        sql.append(" order by ST_Distance(s.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography), s.id limit ?");
        arguments.add(request.getLongitude());
        arguments.add(request.getLatitude());
        arguments.add(request.getSize() + 1);
        List<StoreNearbyResponse> content = jdbcTemplate.query(sql.toString(), (resultSet, rowNum) -> new StoreNearbyResponse(
                resultSet.getLong("id"), resultSet.getString("store_name"), resultSet.getDouble("distance"),
                resultSet.getInt("review_count"), resultSet.getString("store_image"), resultSet.getBoolean("is_open"),
                resultSet.getString("address_line1"), resultSet.getString("address_line2"),
                resultSet.getDouble("latitude"), resultSet.getDouble("longitude")), arguments.toArray());
        return checkLastPage(request.getSize(), content);
    }

    private String escapeLikeKeyword(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private Slice<StoreNearbyResponse> checkLastPage(int size, List<StoreNearbyResponse> content) {
        boolean hasNext = false;
        if (content.size() > size) {
            content.remove(size);
            hasNext = true;
        }
        return new SliceImpl<>(content, PageRequest.of(0, size), hasNext);
    }
}
