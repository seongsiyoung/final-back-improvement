package com.example.finalproject.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
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
                .isEqualTo(400);
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
    void seed_replacesPreviousDatasetAndDistributesKeywordAcrossBothSpatialBuckets() {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(2_000);
        SearchIndexDataSeeder.Dataset replacedDataset = seeder.seed(5_000);

        assertThat(replacedDataset.storeCategoryId()).isNotNull();
        assertThat(replacedDataset.productCategoryId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stores where store_name like 'search-index-store-%'", Integer.class))
                .isEqualTo(5_000);
        assertThat(countStoresWithinRadius(replacedDataset)).isEqualTo(3_000);
        assertThat(countStoresOutsideRadius(replacedDataset)).isEqualTo(2_000);
        assertThat(countKeywordProductsWithinRadius(replacedDataset)).isGreaterThan(0);
        assertThat(countKeywordProductsOutsideRadius(replacedDataset)).isGreaterThan(0);
        assertThat(dataset.keyword()).isEqualTo(replacedDataset.keyword());
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
        return jdbcTemplate.queryForObject("""
                select count(*)
                from products product
                join stores store on store.id = product.store_id
                where product.product_name like ?
                  and %s
                """.formatted(spatialPredicate), Integer.class,
                "%" + dataset.keyword() + "%", dataset.centerLongitude(), dataset.centerLatitude());
    }
}
