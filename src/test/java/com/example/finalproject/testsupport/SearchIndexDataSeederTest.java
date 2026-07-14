package com.example.finalproject.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SearchIndexDataSeederTest extends IntegrationTestSupport {

    @Autowired
    private SearchIndexDataSeeder seeder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seed_createsDeterministicSearchableStoresAndProducts() {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(2_000);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stores where store_name like 'search-index-store-%'", Integer.class))
                .isEqualTo(2_000);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from products where product_name like 'search-index-%' and is_active = true",
                Integer.class))
                .isEqualTo(2_000);
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                from products product
                join stores store on store.id = product.store_id
                where store.store_name like 'search-index-store-%'
                  and lower(product.product_name) like lower(?)
                """, Integer.class, "%" + dataset.keyword() + "%"))
                .isBetween(350, 450);
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                from store_business_hours business_hour
                join stores store on store.id = business_hour.store_id
                where store.store_name like 'search-index-store-%'
                  and business_hour.day_of_week = ?
                  and business_hour.is_closed = false
                """, Integer.class, dataset.todayDayOfWeek()))
                .isEqualTo(2_000);
    }

    @Test
    void seed_replacesPreviousDatasetAndKeepsTheLocalCandidatePoolRealistic() {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(2_000);
        SearchIndexDataSeeder.Dataset replacedDataset = seeder.seed(5_000);

        assertThat(replacedDataset.storeCategoryId()).isNotNull();
        assertThat(replacedDataset.productCategoryId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stores where store_name like 'search-index-store-%'", Integer.class))
                .isEqualTo(5_000);
        assertThat(countStoresWithinRadius(replacedDataset)).isBetween(50, 150);
        assertThat(countStoresOutsideRadius(replacedDataset)).isBetween(4_850, 4_950);
        assertThat(jdbcTemplate.queryForObject("select count(*) from stores where location is null", Integer.class)).isZero();
        assertThat(countKeywordProductsWithinRadius(replacedDataset)).isGreaterThan(0);
        assertThat(countKeywordProductsOutsideRadius(replacedDataset)).isGreaterThan(0);
        assertThat(dataset.keyword()).isEqualTo(replacedDataset.keyword());
    }

    @ParameterizedTest
    @ValueSource(ints = {50_000, 100_000})
    void seed_nationwideProfileDistributesKeywordCandidatesAcrossBothSpatialBuckets(int storeCount) {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(storeCount, SearchIndexDataSeeder.Profile.NATIONWIDE_NORMAL);

        int storesWithinRadius = countStoresWithinRadius(dataset);
        int storesOutsideRadius = countStoresOutsideRadius(dataset);

        assertThat(storesWithinRadius).isBetween(storeCount / 100, storeCount * 3 / 100);
        assertThat(storesOutsideRadius).isBetween(storeCount * 97 / 100, storeCount * 99 / 100);
        assertThat(countProductsByKeyword(dataset, "희소검색어")).isEqualTo(storeCount * 2 / 100);
        assertThat(countProductsByKeyword(dataset, "일반검색어")).isEqualTo(storeCount * 20 / 100);
        assertThat(countProductsByKeywordAndSpatialPredicate(dataset, "희소검색어", true))
                .isBetween(storesWithinRadius / 100, storesWithinRadius * 3 / 100);
        assertThat(countProductsByKeywordAndSpatialPredicate(dataset, "희소검색어", false))
                .isBetween(storesOutsideRadius / 100, storesOutsideRadius * 3 / 100);
        assertThat(countProductsByKeywordAndSpatialPredicate(dataset, "일반검색어", true))
                .isBetween(storesWithinRadius / 10, storesWithinRadius * 3 / 10);
        assertThat(countProductsByKeywordAndSpatialPredicate(dataset, "일반검색어", false))
                .isBetween(storesOutsideRadius / 10, storesOutsideRadius * 3 / 10);
    }

    @Test
    void seed_nationwideProfileCreatesTwoCharacterKeywordCandidatesForTheFirstPage() {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(50_000, SearchIndexDataSeeder.Profile.NATIONWIDE_NORMAL);

        assertThat(countProductsByKeyword(dataset, dataset.shortKeyword())).isBetween(9_000, 11_000);
        assertThat(countProductsByKeywordAndSpatialPredicate(dataset, dataset.shortKeyword(), true)).isGreaterThanOrEqualTo(11);
        assertThat(countProductsByKeywordAndSpatialPredicate(dataset, dataset.shortKeyword(), false)).isGreaterThan(0);
    }

    @Test
    void seed_nationwideDenseProfileKeepsTheCommercialCenterBelowTwoThousandTwoHundredStores() {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(100_000, SearchIndexDataSeeder.Profile.NATIONWIDE_DENSE);

        assertThat(countStoresWithinRadius(dataset)).isBetween(1_800, 2_200);
        assertThat(countStoresOutsideRadius(dataset)).isBetween(97_800, 98_200);
    }

    private int countStoresWithinRadius(SearchIndexDataSeeder.Dataset dataset) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from stores
                where store_name like 'search-index-store-%'
                  and ST_DWithin(location,
                      ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 3000)
                """, Integer.class, dataset.centerLongitude(), dataset.centerLatitude());
    }

    private int countStoresOutsideRadius(SearchIndexDataSeeder.Dataset dataset) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from stores
                where store_name like 'search-index-store-%'
                  and not ST_DWithin(location,
                      ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 3000)
                """, Integer.class, dataset.centerLongitude(), dataset.centerLatitude());
    }

    private int countKeywordProductsWithinRadius(SearchIndexDataSeeder.Dataset dataset) {
        return countKeywordProducts(dataset, "ST_DWithin(store.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 3000)");
    }

    private int countKeywordProductsOutsideRadius(SearchIndexDataSeeder.Dataset dataset) {
        return countKeywordProducts(dataset, "not ST_DWithin(store.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 3000)");
    }

    private int countKeywordProducts(SearchIndexDataSeeder.Dataset dataset, String spatialPredicate) {
        String sql = """
                select count(*)
                from products product
                join stores store on store.id = product.store_id
                where product.product_name like ?
                  and %s
                """.replace("%s", spatialPredicate);
        return jdbcTemplate.queryForObject(sql, Integer.class,
                "%" + dataset.keyword() + "%", dataset.centerLongitude(), dataset.centerLatitude());
    }

    private int countProductsByKeyword(SearchIndexDataSeeder.Dataset dataset, String keyword) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from products product
                join stores store on store.id = product.store_id
                where store.store_name like 'search-index-store-%'
                  and product.product_name like ?
                """, Integer.class, "%" + keyword + "%");
    }

    private int countProductsByKeywordAndSpatialPredicate(SearchIndexDataSeeder.Dataset dataset, String keyword,
                                                           boolean withinRadius) {
        String sql = """
                select count(*)
                from products product
                join stores store on store.id = product.store_id
                where store.store_name like 'search-index-store-%'
                  and product.product_name like ?
                  and %s ST_DWithin(store.location,
                      ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 3000)
                """.replace("%s", withinRadius ? "" : "not ");
        return jdbcTemplate.queryForObject(sql, Integer.class,
                "%" + keyword + "%", dataset.centerLongitude(), dataset.centerLatitude());
    }
}
