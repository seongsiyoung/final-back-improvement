package com.example.finalproject.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.store.dto.response.StoreNearbyResponse;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.SearchIndexDataSeeder;
import com.example.finalproject.testsupport.SqlCaptureInspector;
import com.example.finalproject.user.dto.request.GetStoreSearchRequest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@EnabledIfSystemProperty(named = "runSearchIndexMeasurement", matches = "true")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.example.finalproject.testsupport.SqlCaptureInspector")
class SearchIndexScenarioMeasurementTest extends IntegrationTestSupport {

    private static final String GIST_INDEX = "idx_stores_location_gist";
    private static final String GIN_INDEX = "idx_products_lower_name_trgm_gin";
    private static final int MEASUREMENT_ROUNDS = 4;

    @Autowired
    private SearchIndexDataSeeder seeder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StoreRepository storeRepository;

    @ParameterizedTest
    @MethodSource("scenarioAndStoreCounts")
    void recordsPlanForEachActualSearchScenario(Scenario scenario, int storeCount) {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(storeCount, scenario.profile());
        GetStoreSearchRequest request = GetStoreSearchRequest.builder()
                .latitude(dataset.centerLatitude())
                .longitude(dataset.centerLongitude())
                .keyword(keyword(dataset, scenario))
                .size(10)
                .build();
        SqlCaptureInspector.clear();
        Slice<StoreNearbyResponse> repositoryPage = storeRepository.findNearbyStoresByCategory(request);
        String sql = capturedSpatialSql();
        Object[] arguments = querydslArguments(dataset, scenario);
        assertThat(jdbcTemplate.query(sql, (resultSet, rowNum) -> resultSet.getLong("id"), arguments).stream()
                .limit(10).toList())
                .isEqualTo(repositoryPage.getContent().stream()
                        .map(StoreNearbyResponse::getStoreId)
                        .toList());
        Map<IndexCombination, List<Long>> resultIdsByCombination = new EnumMap<>(IndexCombination.class);
        Map<IndexCombination, List<Measurement>> measurementsByCombination = new EnumMap<>(IndexCombination.class);

        for (int round = 0; round < MEASUREMENT_ROUNDS; round++) {
            for (IndexCombination combination : rotatedCombinations(scenario.combinations(), round)) {
                recreateIndexes(combination);
                List<Long> ids = jdbcTemplate.query(sql, (resultSet, rowNum) -> resultSet.getLong("id"), arguments);
                resultIdsByCombination.putIfAbsent(combination, ids);
                assertThat(ids).isEqualTo(resultIdsByCombination.get(combination));

                measure(sql, arguments);
                List<Measurement> measurements = List.of(
                        measure(sql, arguments), measure(sql, arguments), measure(sql, arguments));
                measurementsByCombination.computeIfAbsent(combination, ignored -> new ArrayList<>()).addAll(measurements);
                assertThat(measurements).allSatisfy(measurement -> assertThat(measurement.plan()).contains("Execution Time"));
            }
        }

        assertThat(resultIdsByCombination.get(IndexCombination.NONE)).isNotEmpty().hasSizeLessThanOrEqualTo(11);
        assertThat(resultIdsByCombination.values()).allSatisfy(
                ids -> assertThat(ids).isEqualTo(resultIdsByCombination.get(IndexCombination.NONE)));
        measurementsByCombination.forEach((combination, measurements) -> {
            Measurement sample = measurements.getFirst();
            System.out.printf("search-scenario=%s stores=%d indexes=%s results=%d medianMs=%.3f "
                            + "medianSharedHit=%d medianSharedRead=%d gistUsed=%s ginUsed=%s%n%s%n",
                    scenario, storeCount, combination, resultIdsByCombination.get(combination).size(), median(measurements),
                    medianSharedBuffers(measurements, Measurement::sharedHit),
                    medianSharedBuffers(measurements, Measurement::sharedRead),
                    measurements.stream().anyMatch(measurement -> measurement.plan().contains(GIST_INDEX)),
                    measurements.stream().anyMatch(measurement -> measurement.plan().contains(GIN_INDEX)), sample.plan());
        });
    }

    private String capturedSpatialSql() {
        return SqlCaptureInspector.capturedSql().stream()
                .filter(sql -> sql.toLowerCase().contains("st_dwithin"))
                .findFirst()
                .orElseThrow();
    }

    private Object[] querydslArguments(SearchIndexDataSeeder.Dataset dataset, Scenario scenario) {
        String point = "SRID=4326;POINT(" + dataset.centerLongitude() + " " + dataset.centerLatitude() + ")";
        List<Object> arguments = new ArrayList<>(List.of(
                point, 0.0, 0, "ACTIVE", true, point, 3000.0, "APPROVED", "ACTIVE",
                dataset.todayDayOfWeek(), false));
        if (scenario.hasKeyword()) {
            arguments.add(true);
            arguments.add("%" + keyword(dataset, scenario) + "%");
        }
        arguments.add(point);
        arguments.add(11);
        return arguments.toArray();
    }

    private String keyword(SearchIndexDataSeeder.Dataset dataset, Scenario scenario) {
        if (!scenario.hasKeyword()) {
            return null;
        }
        if (scenario.specificKeyword()) {
            return dataset.specificKeyword();
        }
        return scenario.shortKeyword() ? dataset.shortKeyword() : dataset.keyword();
    }

    private Measurement measure(String sql, Object[] arguments) {
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class, arguments));
        String executionTime = plan.lines()
                .filter(line -> line.startsWith("Execution Time:"))
                .findFirst()
                .orElseThrow();
        String rootBuffers = plan.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("Buffers: shared"))
                .findFirst()
                .orElse("Buffers: shared");
        return new Measurement(plan, Double.parseDouble(executionTime.replaceAll("[^0-9.]", "")),
                bufferValue(rootBuffers, "hit"), bufferValue(rootBuffers, "read"));
    }

    private double median(List<Measurement> measurements) {
        List<Double> times = measurements.stream().map(Measurement::executionTimeMs).sorted().toList();
        int upperMiddle = times.size() / 2;
        return (times.get(upperMiddle - 1) + times.get(upperMiddle)) / 2;
    }

    private long medianSharedBuffers(List<Measurement> measurements,
                                     java.util.function.ToLongFunction<Measurement> bufferExtractor) {
        List<Long> values = measurements.stream().mapToLong(bufferExtractor).sorted().boxed().toList();
        int upperMiddle = values.size() / 2;
        return (values.get(upperMiddle - 1) + values.get(upperMiddle)) / 2;
    }

    private long bufferValue(String buffers, String name) {
        return java.util.regex.Pattern.compile("\\b" + name + "=(\\d+)")
                .matcher(buffers)
                .results()
                .mapToLong(match -> Long.parseLong(match.group(1)))
                .findFirst()
                .orElse(0);
    }

    private List<IndexCombination> rotatedCombinations(List<IndexCombination> combinations, int round) {
        List<IndexCombination> rotated = new ArrayList<>(combinations);
        java.util.Collections.rotate(rotated, -round);
        return rotated;
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

    private static Stream<Arguments> scenarioAndStoreCounts() {
        return Stream.of(System.getProperty("searchIndexMeasurementStoreCount", "2000,5000,50000,100000").split(","))
                .map(Integer::parseInt)
                .flatMap(storeCount -> Stream.of(Scenario.values())
                        .filter(scenario -> scenario.matchesStoreCount(storeCount))
                        .map(scenario -> Arguments.of(scenario, storeCount)));
    }

    private enum Scenario {
        MAIN_PAGE_NATIONWIDE(SearchIndexDataSeeder.Profile.NATIONWIDE_NORMAL, false, false, false,
                List.of(IndexCombination.NONE, IndexCombination.GIST_ONLY)),
        MAIN_PAGE_DENSE(SearchIndexDataSeeder.Profile.NATIONWIDE_DENSE, false, false, false,
                List.of(IndexCombination.NONE, IndexCombination.GIST_ONLY)),
        SPECIFIC_KEYWORD(SearchIndexDataSeeder.Profile.NATIONWIDE_NORMAL, true, true, false, List.of(IndexCombination.values())),
        BROAD_KEYWORD(SearchIndexDataSeeder.Profile.NATIONWIDE_NORMAL, true, false, false, List.of(IndexCombination.values())),
        SHORT_KEYWORD(SearchIndexDataSeeder.Profile.NATIONWIDE_NORMAL, true, false, true, List.of(IndexCombination.values()));

        private final SearchIndexDataSeeder.Profile profile;
        private final boolean hasKeyword;
        private final boolean specificKeyword;
        private final boolean shortKeyword;
        private final List<IndexCombination> combinations;

        Scenario(SearchIndexDataSeeder.Profile profile, boolean hasKeyword, boolean specificKeyword, boolean shortKeyword,
                 List<IndexCombination> combinations) {
            this.profile = profile;
            this.hasKeyword = hasKeyword;
            this.specificKeyword = specificKeyword;
            this.shortKeyword = shortKeyword;
            this.combinations = combinations;
        }

        SearchIndexDataSeeder.Profile profile() { return profile; }
        boolean hasKeyword() { return hasKeyword; }
        boolean specificKeyword() { return specificKeyword; }
        boolean shortKeyword() { return shortKeyword; }
        List<IndexCombination> combinations() { return combinations; }

        boolean matchesStoreCount(int storeCount) {
            return !(specificKeyword || shortKeyword) || storeCount >= 50_000;
        }
    }

    private enum IndexCombination {
        NONE(false, false), GIST_ONLY(true, false), GIN_ONLY(false, true), BOTH(true, true);

        private final boolean gist;
        private final boolean gin;

        IndexCombination(boolean gist, boolean gin) {
            this.gist = gist;
            this.gin = gin;
        }

        boolean gist() { return gist; }
        boolean gin() { return gin; }
    }

    private record Measurement(String plan, double executionTimeMs, long sharedHit, long sharedRead) {
    }
}
