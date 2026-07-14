package com.example.finalproject.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.SearchIndexDataSeeder;
import com.example.finalproject.testsupport.SqlCaptureInspector;
import com.example.finalproject.user.dto.request.GetStoreSearchRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@EnabledIfSystemProperty(named = "runSearchIndexMeasurement", matches = "true")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.example.finalproject.testsupport.SqlCaptureInspector")
class SearchIndexSpatialPredicateTest extends IntegrationTestSupport {

    private static final String GIST_INDEX = "idx_stores_location_gist";

    @Autowired
    private SearchIndexDataSeeder seeder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StoreRepository storeRepository;

    @Test
    void bareSpatialPredicate_preservesFirstPageResultsAndUsesGist() {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(100_000, SearchIndexDataSeeder.Profile.NATIONWIDE_NORMAL);
        Object[] arguments = {dataset.centerLongitude(), dataset.centerLatitude(), dataset.centerLongitude(),
                dataset.centerLatitude(), dataset.todayDayOfWeek(), dataset.centerLongitude(), dataset.centerLatitude()};

        SqlCaptureInspector.clear();
        List<Long> repositoryIds = storeRepository.findNearbyStoresByCategory(GetStoreSearchRequest.builder()
                        .latitude(dataset.centerLatitude())
                        .longitude(dataset.centerLongitude())
                        .size(10)
                        .build())
                .getContent().stream().map(store -> store.getStoreId()).toList();

        String querydslSql = spatialSql(SqlCaptureInspector.capturedSql());
        assertGeographySpatialFunctions(querydslSql);

        List<Long> currentIds = ids(searchSql(true), arguments);
        List<Long> candidateIds = ids(searchSql(false), arguments);

        assertThat(currentIds).hasSize(11);
        assertThat(currentIds.stream().limit(10).toList()).isEqualTo(repositoryIds);
        assertThat(candidateIds).isEqualTo(currentIds);

        recreateGist();

        assertThat(plan(querydslSql, querydslArguments(dataset))).contains(GIST_INDEX);
        assertThat(plan(searchSql(true), arguments)).doesNotContain(GIST_INDEX);
        assertThat(plan(searchSql(false), arguments)).contains(GIST_INDEX);
    }

    private List<Long> ids(String sql, Object[] arguments) {
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> resultSet.getLong("id"), arguments);
    }

    private String plan(String sql, Object[] arguments) {
        return String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class, arguments));
    }

    private void recreateGist() {
        jdbcTemplate.execute("drop index if exists " + GIST_INDEX);
        jdbcTemplate.execute("create index " + GIST_INDEX + " on stores using gist (location)");
        jdbcTemplate.execute("analyze stores");
    }

    private String spatialSql(List<String> capturedSql) {
        return capturedSql.stream()
                .filter(sql -> sql.toLowerCase().contains("st_dwithin"))
                .findFirst()
                .orElseThrow();
    }

    private void assertGeographySpatialFunctions(String spatialSql) {
        assertThat(spatialSql).containsPattern(
                "(?is)st_dwithin\\s*\\([^,]+,\\s*cast\\(\\?\\s+as\\s+geography\\),\\s*\\?\\s*\\)");
        assertThat(spatialSql).containsPattern(
                "(?is)st_distance\\s*\\([^,]+,\\s*cast\\(\\?\\s+as\\s+geography\\)\\s*\\)");
        assertThat(spatialSql).doesNotContainPattern("(?is)st_dwithin\\s*\\([^)]*\\)\\s+is\\s+true");
    }

    private Object[] querydslArguments(SearchIndexDataSeeder.Dataset dataset) {
        String point = "SRID=4326;POINT(" + dataset.centerLongitude() + " " + dataset.centerLatitude() + ")";
        return new Object[] {point, 0.0, 0, "ACTIVE", true, point, 3000.0, "APPROVED", "ACTIVE",
                dataset.todayDayOfWeek(), false, point, 11};
    }

    private String searchSql(boolean isTrueWrapped) {
        return """
                select s.id, s.store_name,
                       ST_Distance(s.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography),
                       coalesce(s.review_count, 0), s.store_image,
                       s.is_active = 'ACTIVE' and s.is_delivery_available = true,
                       s.address_line1, s.address_line2,
                       ST_Y(ST_GeometryFromText(ST_AsText(s.location))),
                       ST_X(ST_GeometryFromText(ST_AsText(s.location)))
                from stores s
                where ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 3000)%s
                  and s.status = 'APPROVED'
                  and s.is_active = 'ACTIVE'
                  and s.deleted_at is null
                  and s.id in (
                      select bh.store_id from store_business_hours bh
                      where bh.day_of_week = ? and bh.is_closed = false
                  )
                order by ST_Distance(s.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography), s.id
                limit 11
                """.formatted(isTrueWrapped ? " is true" : "");
    }
}
