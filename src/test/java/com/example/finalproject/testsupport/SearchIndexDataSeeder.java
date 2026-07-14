package com.example.finalproject.testsupport;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchIndexDataSeeder {

    private static final String STORE_PREFIX = "search-index-store-";
    private static final String USER_EMAIL_PREFIX = "search-index-owner-";
    private static final String USER_EMAIL_SUFFIX = "@test.local";
    private static final String PRODUCT_PREFIX = "search-index-";
    private static final String KEYWORD = "포폴매칭";
    private static final double CENTER_LONGITUDE = 127.0276;
    private static final double CENTER_LATITUDE = 37.4979;

    private final JdbcTemplate jdbcTemplate;

    public Dataset seed(int storeCount) {
        short todayDayOfWeek = (short) (LocalDate.now().getDayOfWeek().getValue() % 7);
        clearPreviousDataset();

        Long storeCategoryId = findOrCreateStoreCategory();
        Long productCategoryId = findOrCreateProductCategory();
        insertUsers(storeCount);
        insertStores(storeCount, storeCategoryId);
        insertProducts(productCategoryId);
        insertBusinessHours(todayDayOfWeek);
        jdbcTemplate.execute("ANALYZE users; ANALYZE stores; ANALYZE products; ANALYZE store_business_hours");

        return new Dataset(storeCategoryId, productCategoryId, CENTER_LONGITUDE, CENTER_LATITUDE,
                KEYWORD, todayDayOfWeek);
    }

    private void clearPreviousDataset() {
        jdbcTemplate.update("delete from store_business_hours where store_id in "
                + "(select id from stores where store_name like ?)", STORE_PREFIX + "%");
        jdbcTemplate.update("delete from products where store_id in "
                + "(select id from stores where store_name like ?)", STORE_PREFIX + "%");
        jdbcTemplate.update("delete from stores where store_name like ?", STORE_PREFIX + "%");
        jdbcTemplate.update("delete from users where email like ?", USER_EMAIL_PREFIX + "%" + USER_EMAIL_SUFFIX);
        jdbcTemplate.execute("vacuum analyze store_business_hours");
        jdbcTemplate.execute("vacuum analyze products");
        jdbcTemplate.execute("vacuum analyze stores");
        jdbcTemplate.execute("vacuum analyze users");
    }

    private Long findOrCreateStoreCategory() {
        return jdbcTemplate.queryForObject("""
                insert into store_categories (category_name)
                values ('search-index-category')
                on conflict (category_name) do update set category_name = excluded.category_name
                returning id
                """, Long.class);
    }

    private Long findOrCreateProductCategory() {
        return jdbcTemplate.queryForObject("""
                insert into categories (created_at, updated_at, category_name, icon_url)
                values (now(), now(), 'search-index-product-category', null)
                on conflict (category_name) do update set category_name = excluded.category_name
                returning id
                """, Long.class);
    }

    private void insertUsers(int storeCount) {
        jdbcTemplate.update("""
                insert into users (created_at, updated_at, email, password, name, phone, status,
                                   terms_agreed, privacy_agreed, terms_agreed_at, privacy_agreed_at,
                                   points, token_version)
                select now(), now(),
                       ? || series || ?,
                       'not-used',
                       '검색 인덱스 소유자',
                       '09' || lpad(series::text, 9, '0'),
                       'ACTIVE', true, true, now(), now(), 0, 0
                from generate_series(1, ?) as series
                """, USER_EMAIL_PREFIX, USER_EMAIL_SUFFIX, storeCount);
    }

    private void insertStores(int storeCount, Long storeCategoryId) {
        jdbcTemplate.update("""
                insert into stores (created_at, updated_at, owner_id, store_category_id, store_name,
                                    representative_name, representative_phone, business_owner_name,
                                    business_number, telecom_sales_report_number, postal_code, address_line1,
                                    location, settlement_bank_name, settlement_bank_account,
                                    settlement_account_holder, review_count, status, is_delivery_available,
                                    is_active, commission_rate)
                select now(), now(), u.id, ?,
                       ? || row_number() over (order by u.id),
                       '검색 인덱스 대표자', '01000000000', '검색 인덱스 대표자',
                       lpad(row_number() over (order by u.id)::text, 12, '0'),
                       'search-index-report-' || row_number() over (order by u.id),
                       '06134', '검색 인덱스 주소',
                       ST_SetSRID(ST_MakePoint(
                           ? + case when row_number() over (order by u.id) % 5 < 3
                                    then (row_number() over (order by u.id) % 100) * 0.0001
                                    else 0.05 + (row_number() over (order by u.id) % 100) * 0.0001 end,
                           ?), 4326)::geography,
                       '검색은행', '110-000-000000', '검색 인덱스 대표자',
                       0, 'APPROVED', true, 'ACTIVE', 5.00
                from users u
                where u.email like ? || '%' || ?
                order by u.id
                limit ?
                """, storeCategoryId, STORE_PREFIX, CENTER_LONGITUDE, CENTER_LATITUDE,
                USER_EMAIL_PREFIX, USER_EMAIL_SUFFIX, storeCount);
    }

    private void insertProducts(Long productCategoryId) {
        jdbcTemplate.update("""
                insert into products (created_at, updated_at, store_id, category_id, product_name,
                                      price, stock, is_active, order_count)
                select now(), now(), s.id, ?,
                       ? || case when row_number() over (order by s.id) % 10 in (0, 4)
                                then ? else '포폴비매칭' end || '-' || row_number() over (order by s.id),
                       1000, 100, true, 0
                from stores s
                where s.store_name like ?
                """, productCategoryId, PRODUCT_PREFIX, KEYWORD, STORE_PREFIX + "%");
    }

    private void insertBusinessHours(short todayDayOfWeek) {
        jdbcTemplate.update("""
                insert into store_business_hours (created_at, updated_at, store_id, day_of_week,
                                                  open_time, close_time, is_closed)
                select now(), now(), id, ?, time '09:00', time '21:00', false
                from stores
                where store_name like ?
                """, todayDayOfWeek, STORE_PREFIX + "%");
    }

    public record Dataset(Long storeCategoryId, Long productCategoryId, double centerLongitude,
                          double centerLatitude, String keyword, short todayDayOfWeek) {
    }
}
