package com.example.finalproject.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.SearchIndexDataSeeder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfSystemProperty(named = "runSearchIndexMeasurement", matches = "true")
class StoreSearchApiMeasurementTest extends IntegrationTestSupport {

    private static final String GIST_INDEX = "idx_stores_location_gist";
    private static final int WARMUP_REQUESTS = 5;
    private static final int MEASUREMENT_REQUESTS = 20;

    @Autowired
    private SearchIndexDataSeeder seeder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @ParameterizedTest
    @MethodSource("storeCounts")
    void measuresNearbyStoreApiResponseWithGist(int storeCount) {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(storeCount, SearchIndexDataSeeder.Profile.NATIONWIDE_NORMAL);
        ensureGist();
        String url = "/api/users/stores?latitude=" + dataset.centerLatitude()
                + "&longitude=" + dataset.centerLongitude() + "&size=10";

        for (int request = 0; request < WARMUP_REQUESTS; request++) {
            assertSuccessfulResponse(url);
        }

        List<Double> responseTimesMs = new ArrayList<>();
        for (int request = 0; request < MEASUREMENT_REQUESTS; request++) {
            long startedAt = System.nanoTime();
            assertSuccessfulResponse(url);
            responseTimesMs.add((System.nanoTime() - startedAt) / 1_000_000.0);
        }

        System.out.printf("api-nearby-search stores=%d warmup=%d samples=%d p50Ms=%.3f p95Ms=%.3f%n",
                storeCount, WARMUP_REQUESTS, MEASUREMENT_REQUESTS,
                percentile(responseTimesMs, 0.50), percentile(responseTimesMs, 0.95));
    }

    private void ensureGist() {
        jdbcTemplate.execute("create index if not exists " + GIST_INDEX + " on stores using gist (location)");
        jdbcTemplate.execute("analyze stores");
    }

    private void assertSuccessfulResponse(String url) {
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"success\":true", "\"content\"");
    }

    private double percentile(List<Double> samples, double percentile) {
        List<Double> sorted = samples.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(index);
    }

    private static Stream<Arguments> storeCounts() {
        return Stream.of(System.getProperty("searchIndexMeasurementStoreCount", "50000,100000").split(","))
                .map(Integer::parseInt)
                .map(Arguments::of);
    }
}
