package com.example.finalproject.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.SearchIndexDataSeeder;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfSystemProperty(named = "runSearchIndexMeasurement", matches = "true")
class SearchIndexMeasurementTest extends IntegrationTestSupport {

    private static final String GIST_INDEX = "idx_stores_location_gist";
    private static final String GIN_INDEX = "idx_products_lower_name_trgm_gin";
    private static final String SEARCH_SQL = """
            select s.id,
                   s.store_name,
                   ST_Distance(s.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography),
                   coalesce(s.review_count, 0),
                   s.store_image,
                   s.is_active = 'ACTIVE' and s.is_delivery_available = true,
                   s.address_line1,
                   s.address_line2,
                   ST_Y(ST_GeometryFromText(ST_AsText(s.location))),
                   ST_X(ST_GeometryFromText(ST_AsText(s.location)))
            from stores s
            where ST_DWithin(s.location,
                             ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                             3000) is true
              and s.status = 'APPROVED'
              and s.is_active = 'ACTIVE'
              and s.deleted_at is null
              and s.store_category_id = ?
              and s.id in (
                  select bh.store_id
                  from store_business_hours bh
                  where bh.day_of_week = ? and bh.is_closed = false
              )
              and exists (
                  select 1
                  from products p
                  where p.store_id = s.id
                    and p.is_active = true
                    and lower(p.product_name) like '%' || lower(?) || '%'
              )
            order by ST_Distance(s.location,
                                 ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography), s.id
            limit 11
            """;

    @Autowired
    private SearchIndexDataSeeder seeder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @MethodSource("storeCounts")
    void recordsExecutionPlanForEachIndexCombination(int storeCount) {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(storeCount);
        Map<IndexCombination, List<Long>> resultIdsByCombination = new EnumMap<>(IndexCombination.class);
        Map<IndexCombination, List<Measurement>> measurementsByCombination = new EnumMap<>(IndexCombination.class);

        for (int round = 0; round < IndexCombination.values().length; round++) {
            for (IndexCombination combination : rotatedCombinations(storeCount, round)) {
                recreateIndexes(combination);
                List<Long> resultIds = jdbcTemplate.query(SEARCH_SQL,
                        (resultSet, rowNum) -> resultSet.getLong("id"), queryArguments(dataset));
                resultIdsByCombination.putIfAbsent(combination, resultIds);
                assertThat(resultIds).isEqualTo(resultIdsByCombination.get(combination));

                measure(dataset);
                List<Measurement> measurements = List.of(measure(dataset), measure(dataset), measure(dataset));
                measurementsByCombination.computeIfAbsent(combination, ignored -> new ArrayList<>()).addAll(measurements);
                assertThat(measurements).allSatisfy(measurement -> assertThat(measurement.plan())
                        .contains("Execution Time"));
            }
        }

        assertThat(resultIdsByCombination.get(IndexCombination.NONE)).hasSize(11);
        assertThat(resultIdsByCombination.values()).allSatisfy(
                resultIds -> assertThat(resultIds).isEqualTo(resultIdsByCombination.get(IndexCombination.NONE)));

        measurementsByCombination.forEach((combination, measurements) -> {
            Measurement sample = measurements.getFirst();
            System.out.printf("search-index stores=%d indexes=%s results=%d medianMs=%.3f gistUsed=%s ginUsed=%s%n%s%n",
                    storeCount, combination, resultIdsByCombination.get(combination).size(), medianExecutionTime(measurements),
                    sample.plan().contains(GIST_INDEX), sample.plan().contains(GIN_INDEX), sample.plan());
        });
    }

    private Object[] queryArguments(SearchIndexDataSeeder.Dataset dataset) {
        return new Object[]{
                dataset.centerLongitude(), dataset.centerLatitude(), dataset.centerLongitude(), dataset.centerLatitude(),
                dataset.storeCategoryId(), dataset.todayDayOfWeek(), dataset.keyword(),
                dataset.centerLongitude(), dataset.centerLatitude()
        };
    }

    private Measurement measure(SearchIndexDataSeeder.Dataset dataset) {
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN (ANALYZE, BUFFERS) " + SEARCH_SQL, String.class, queryArguments(dataset)));
        String executionTime = plan.lines()
                .filter(line -> line.startsWith("Execution Time:"))
                .findFirst()
                .orElseThrow();
        return new Measurement(plan, Double.parseDouble(executionTime.replaceAll("[^0-9.]", "")));
    }

    private List<IndexCombination> rotatedCombinations(int storeCount, int round) {
        List<IndexCombination> combinations = new ArrayList<>(List.of(IndexCombination.values()));
        java.util.Collections.rotate(combinations, -((storeCount / 1000) + round) % combinations.size());
        return combinations;
    }

    private double medianExecutionTime(List<Measurement> measurements) {
        List<Double> sortedExecutionTimes = measurements.stream()
                .map(Measurement::executionTimeMs)
                .sorted()
                .toList();
        int upperMiddleIndex = sortedExecutionTimes.size() / 2;
        return (sortedExecutionTimes.get(upperMiddleIndex - 1) + sortedExecutionTimes.get(upperMiddleIndex)) / 2;
    }

    private static Stream<Integer> storeCounts() {
        return Stream.of(System.getProperty("searchIndexMeasurementStoreCount", "2000,5000,50000,100000")
                .split(","))
                .map(Integer::parseInt);
    }

    private void recreateIndexes(IndexCombination combination) {
        jdbcTemplate.execute("drop index if exists " + GIST_INDEX);
        jdbcTemplate.execute("drop index if exists " + GIN_INDEX);
        jdbcTemplate.execute("create extension if not exists pg_trgm");

        if (combination.gist()) {
            jdbcTemplate.execute("create index " + GIST_INDEX + " on stores using gist (location)");
        }
        if (combination.gin()) {
            jdbcTemplate.execute("create index " + GIN_INDEX
                    + " on products using gin (lower(product_name) gin_trgm_ops)");
        }
        jdbcTemplate.execute("analyze stores");
        jdbcTemplate.execute("analyze products");
    }

    private enum IndexCombination {
        NONE(false, false),
        GIST_ONLY(true, false),
        GIN_ONLY(false, true),
        BOTH(true, true);

        private final boolean gist;
        private final boolean gin;

        IndexCombination(boolean gist, boolean gin) {
            this.gist = gist;
            this.gin = gin;
        }

        boolean gist() {
            return gist;
        }

        boolean gin() {
            return gin;
        }
    }

    private record Measurement(String plan, double executionTimeMs) {
    }
}
