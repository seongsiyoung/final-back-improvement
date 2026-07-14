package com.example.finalproject.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.store.dto.response.StoreNearbyResponse;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.SearchIndexDataSeeder;
import com.example.finalproject.user.dto.request.GetStoreSearchRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Slice;

class StoreRepositoryNativeSpatialSearchTest extends IntegrationTestSupport {

    @Autowired
    private SearchIndexDataSeeder seeder;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void categoryAndCaseInsensitiveKeyword_filterTheSameSearchResultSet() {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(2_000, SearchIndexDataSeeder.Profile.BROAD);
        Long otherCategoryId = jdbcTemplate.queryForObject("""
                insert into store_categories (category_name)
                values ('search-index-other-category')
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

        Slice<StoreNearbyResponse> otherCategoryResult = storeRepository.findNearbyStoresByCategory(GetStoreSearchRequest.builder()
                .latitude(dataset.centerLatitude())
                .longitude(dataset.centerLongitude())
                .storeCategoryId(otherCategoryId)
                .keyword(dataset.keyword().toUpperCase())
                .size(10)
                .build());
        Slice<StoreNearbyResponse> defaultCategoryResult = storeRepository.findNearbyStoresByCategory(GetStoreSearchRequest.builder()
                .latitude(dataset.centerLatitude())
                .longitude(dataset.centerLongitude())
                .storeCategoryId(dataset.storeCategoryId())
                .keyword(dataset.keyword().toUpperCase())
                .size(10)
                .build());

        assertThat(otherCategoryResult.getContent()).extracting(StoreNearbyResponse::getStoreId)
                .containsExactly(otherCategoryStoreId);
        assertThat(otherCategoryResult.hasNext()).isFalse();
        assertThat(defaultCategoryResult.getContent()).extracting(StoreNearbyResponse::getStoreId)
                .doesNotContain(otherCategoryStoreId);
    }

    @Test
    void cursorReturnsTheNextDistanceAndIdOrderedPageWithoutOverlap() {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(2_000, SearchIndexDataSeeder.Profile.BROAD);
        GetStoreSearchRequest firstRequest = baseRequest(dataset).build();
        Slice<StoreNearbyResponse> firstPage = storeRepository.findNearbyStoresByCategory(firstRequest);
        StoreNearbyResponse cursor = firstPage.getContent().getLast();

        Slice<StoreNearbyResponse> secondPage = storeRepository.findNearbyStoresByCategory(baseRequest(dataset)
                .lastDistance(cursor.getDistance())
                .lastId(cursor.getStoreId())
                .build());

        assertThat(firstPage).hasSize(10);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(secondPage).hasSize(10);
        assertThat(secondPage.getContent())
                .extracting(StoreNearbyResponse::getStoreId)
                .doesNotContainAnyElementsOf(ids(firstPage));
        assertDistanceAndIdOrder(firstPage.getContent());
        assertDistanceAndIdOrder(secondPage.getContent());
        StoreNearbyResponse firstSecondPageStore = secondPage.getContent().getFirst();
        assertThat(firstSecondPageStore.getDistance()).isGreaterThanOrEqualTo(cursor.getDistance());
        if (firstSecondPageStore.getDistance().equals(cursor.getDistance())) {
            assertThat(firstSecondPageStore.getStoreId()).isGreaterThan(cursor.getStoreId());
        }
    }

    @Test
    void keywordSpecialCharactersAreMatchedLiterally() {
        SearchIndexDataSeeder.Dataset dataset = seeder.seed(2_000, SearchIndexDataSeeder.Profile.BROAD);
        Long storeId = jdbcTemplate.queryForObject("""
                select s.id from stores s
                where s.store_name like 'search-index-store-%'
                order by s.id
                limit 1
                """, Long.class);
        jdbcTemplate.update("update products set product_name = 'literal%_!token' where store_id = ?", storeId);

        Slice<StoreNearbyResponse> literalResult = storeRepository.findNearbyStoresByCategory(baseRequest(dataset)
                .keyword("literal%_!token")
                .build());
        Slice<StoreNearbyResponse> wildcardLikeResult = storeRepository.findNearbyStoresByCategory(baseRequest(dataset)
                .keyword("literal%token")
                .build());

        assertThat(literalResult.getContent()).extracting(StoreNearbyResponse::getStoreId).containsExactly(storeId);
        assertThat(wildcardLikeResult.getContent()).isEmpty();
    }

    private GetStoreSearchRequest.GetStoreSearchRequestBuilder baseRequest(SearchIndexDataSeeder.Dataset dataset) {
        return GetStoreSearchRequest.builder()
                .latitude(dataset.centerLatitude())
                .longitude(dataset.centerLongitude())
                .size(10);
    }

    private List<Long> ids(Slice<StoreNearbyResponse> page) {
        return page.getContent().stream().map(StoreNearbyResponse::getStoreId).toList();
    }

    private void assertDistanceAndIdOrder(List<StoreNearbyResponse> stores) {
        for (int index = 1; index < stores.size(); index++) {
            StoreNearbyResponse previous = stores.get(index - 1);
            StoreNearbyResponse current = stores.get(index);
            assertThat(current.getDistance()).isGreaterThanOrEqualTo(previous.getDistance());
            if (current.getDistance().equals(previous.getDistance())) {
                assertThat(current.getStoreId()).isGreaterThan(previous.getStoreId());
            }
        }
    }
}
