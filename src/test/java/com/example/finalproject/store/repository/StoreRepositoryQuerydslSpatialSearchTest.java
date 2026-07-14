package com.example.finalproject.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.store.dto.response.StoreNearbyResponse;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.SearchIndexDataSeeder;
import com.example.finalproject.testsupport.SqlCaptureInspector;
import com.example.finalproject.user.dto.request.GetStoreSearchRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.example.finalproject.testsupport.SqlCaptureInspector")
class StoreRepositoryQuerydslSpatialSearchTest extends IntegrationTestSupport {

    @Autowired
    private SearchIndexDataSeeder seeder;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void querydslSpatialSearch_usesGeographyMetersAndMatchesNativeSearch() {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(2_000, SearchIndexDataSeeder.Profile.BROAD);
        Long otherCategoryId = jdbcTemplate.queryForObject("""
                insert into store_categories (category_name)
                values ('search-index-querydsl-other-category')
                on conflict (category_name) do update set category_name = excluded.category_name
                returning id
                """, Long.class);
        Long otherCategoryStoreId = jdbcTemplate.queryForObject("""
                select s.id
                from stores s join products p on p.store_id = s.id
                where s.store_name like 'search-index-store-%'
                  and p.product_name like '%일반검색어%'
                  and ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 3000)
                order by s.id
                limit 1
                """, Long.class, dataset.centerLongitude(), dataset.centerLatitude());
        jdbcTemplate.update("update stores set store_category_id = ? where id = ?", otherCategoryId, otherCategoryStoreId);

        GetStoreSearchRequest defaultCategoryRequest = request(dataset)
                .storeCategoryId(dataset.storeCategoryId())
                .keyword(dataset.keyword().toUpperCase())
                .build();
        GetStoreSearchRequest otherCategoryRequest = request(dataset)
                .storeCategoryId(otherCategoryId)
                .keyword(dataset.keyword().toUpperCase())
                .build();

        Slice<StoreNearbyResponse> querydslResult = storeRepository.findNearbyStoresByCategory(defaultCategoryRequest);

        assertSamePage(nativeSearch(defaultCategoryRequest), querydslResult);
        assertThat(querydslResult.getContent()).extracting(StoreNearbyResponse::getStoreId)
                .doesNotContain(otherCategoryStoreId);
        Slice<StoreNearbyResponse> querydslOtherCategoryResult = storeRepository.findNearbyStoresByCategory(otherCategoryRequest);
        assertSamePage(nativeSearch(otherCategoryRequest), querydslOtherCategoryResult);
        assertThat(querydslOtherCategoryResult.getContent()).extracting(StoreNearbyResponse::getStoreId)
                .containsExactly(otherCategoryStoreId);
        assertThat(jdbcTemplate.queryForObject("select pg_typeof(location)::text from stores limit 1", String.class))
                .isEqualTo("geography");
    }

    @Test
    void querydslSpatialSearch_preservesCursorAndLiteralKeywordContractsAgainstNativeSearch() {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(2_000, SearchIndexDataSeeder.Profile.BROAD);
        Slice<StoreNearbyResponse> firstPage = storeRepository.findNearbyStoresByCategory(request(dataset).build());
        StoreNearbyResponse cursor = firstPage.getContent().getLast();
        GetStoreSearchRequest nextPageRequest = request(dataset)
                .lastDistance(cursor.getDistance())
                .lastId(cursor.getStoreId())
                .build();

        assertSamePage(nativeSearch(nextPageRequest), storeRepository.findNearbyStoresByCategory(nextPageRequest));

        Long storeId = jdbcTemplate.queryForObject("""
                select s.id from stores s
                where s.store_name like 'search-index-store-%'
                order by s.id
                limit 1
                """, Long.class);
        jdbcTemplate.update("update products set product_name = 'literal%_!token' where store_id = ?", storeId);
        GetStoreSearchRequest literalRequest = request(dataset).keyword("literal%_!token").build();

        Slice<StoreNearbyResponse> querydslLiteralResult = storeRepository.findNearbyStoresByCategory(literalRequest);

        assertSamePage(nativeSearch(literalRequest), querydslLiteralResult);
        assertThat(querydslLiteralResult.getContent()).extracting(StoreNearbyResponse::getStoreId).containsExactly(storeId);
    }

    @Test
    void repositorySearch_rendersBareGeographySpatialFunctionsInsteadOfNativeSql() {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(2_000, SearchIndexDataSeeder.Profile.BROAD);
        SqlCaptureInspector.clear();

        storeRepository.findNearbyStoresByCategory(request(dataset).build());

        assertGeographySpatialFunctions(SqlCaptureInspector.capturedSql());
    }

    private Slice<StoreNearbyResponse> nativeSearch(GetStoreSearchRequest request) {
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
        List<Object> arguments = new ArrayList<>(List.of(
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
        boolean hasNext = content.size() > request.getSize();
        if (hasNext) {
            content.remove(request.getSize().intValue());
        }
        return new SliceImpl<>(content, PageRequest.of(0, request.getSize()), hasNext);
    }

    private String escapeLikeKeyword(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private void assertGeographySpatialFunctions(List<String> capturedSql) {
        String spatialSql = capturedSql.stream()
                .filter(sql -> sql.toLowerCase().contains("st_dwithin"))
                .findFirst()
                .orElseThrow();
        assertThat(spatialSql).containsPattern(
                "(?is)st_dwithin\\s*\\([^,]+,\\s*cast\\(\\?\\s+as\\s+geography\\),\\s*\\?\\s*\\)");
        assertThat(spatialSql).containsPattern(
                "(?is)st_distance\\s*\\([^,]+,\\s*cast\\(\\?\\s+as\\s+geography\\)\\s*\\)");
        assertThat(spatialSql).doesNotContainPattern("(?is)st_dwithin\\s*\\([^)]*\\)\\s+is\\s+true");
    }

    private GetStoreSearchRequest.GetStoreSearchRequestBuilder request(SearchIndexDataSeeder.Dataset dataset) {
        return GetStoreSearchRequest.builder()
                .latitude(dataset.centerLatitude())
                .longitude(dataset.centerLongitude())
                .size(10);
    }

    private void assertSamePage(Slice<StoreNearbyResponse> expected, Slice<StoreNearbyResponse> actual) {
        assertThat(actual.hasNext()).isEqualTo(expected.hasNext());
        assertThat(actual.getContent()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(expected.getContent());
    }
}
