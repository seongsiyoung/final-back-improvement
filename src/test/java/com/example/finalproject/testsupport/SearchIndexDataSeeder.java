package com.example.finalproject.testsupport;

import java.time.LocalDate;
import java.util.List;
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
    private static final String KEYWORD = "일반검색어";
    private static final String SPECIFIC_KEYWORD = "희소검색어";
    private static final String SHORT_KEYWORD = "두글";
    private static final double CENTER_LONGITUDE = 127.0276;
    private static final double CENTER_LATITUDE = 37.4979;
    private static final List<Region> REGIONS = List.of(
            new Region(228, 126.85, 37.65), new Region(61, 129.08, 35.18), new Region(40, 128.60, 35.87),
            new Region(49, 126.70, 37.46), new Region(27, 126.85, 35.16), new Region(28, 127.38, 36.35),
            new Region(22, 129.31, 35.54), new Region(6, 127.29, 36.48), new Region(243, 127.25, 37.41),
            new Region(29, 127.73, 37.88), new Region(33, 127.49, 36.64), new Region(43, 127.11, 36.81),
            new Region(31, 127.15, 35.82), new Region(35, 126.85, 35.16), new Region(50, 128.68, 36.57),
            new Region(61, 128.68, 35.23), new Region(14, 126.53, 33.50));

    private final JdbcTemplate jdbcTemplate;

    public Dataset seed(int storeCount) {
        return seed(storeCount, Profile.NATIONWIDE_NORMAL);
    }

    public Dataset seed(int storeCount, Profile profile) {
        short todayDayOfWeek = (short) (LocalDate.now().getDayOfWeek().getValue() % 7);
        clearPreviousDataset();

        Long storeCategoryId = findOrCreateStoreCategory();
        Long productCategoryId = findOrCreateProductCategory();
        insertUsers(storeCount);
        insertStores(storeCount, storeCategoryId, profile);
        insertProducts(productCategoryId, profile);
        insertBusinessHours(todayDayOfWeek);
        jdbcTemplate.execute("ANALYZE users; ANALYZE stores; ANALYZE products; ANALYZE store_business_hours");

        return new Dataset(storeCategoryId, productCategoryId, CENTER_LONGITUDE, CENTER_LATITUDE,
                KEYWORD, SPECIFIC_KEYWORD, SHORT_KEYWORD, todayDayOfWeek);
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

    private void insertStores(int storeCount, Long storeCategoryId, Profile profile) {
        if (profile.nationwide()) {
            insertNationwideStores(storeCount, storeCategoryId, profile);
            return;
        }
        if (profile == Profile.CITYWIDE) {
            insertCitywideStores(storeCount, storeCategoryId);
            return;
        }
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
                           ? + case when row_number() over (order by u.id) % ? < ?
                                    then ((row_number() over (order by u.id) * 53 % 101) - 50) * 0.0002
                                    else 0.05 + ((row_number() over (order by u.id) * 53 % 101) - 50) * 0.0002 end,
                           ? + ((row_number() over (order by u.id) * 97 % 101) - 50) * 0.0002), 4326)::geography,
                       '검색은행', '110-000-000000', '검색 인덱스 대표자',
                       0, 'APPROVED', true, 'ACTIVE', 5.00
                from users u
                where u.email like ? || '%' || ?
                order by u.id
                limit ?
                """, storeCategoryId, STORE_PREFIX, CENTER_LONGITUDE, profile.spatialModulo(), profile.nearbyRows(), CENTER_LATITUDE,
                USER_EMAIL_PREFIX, USER_EMAIL_SUFFIX, storeCount);
    }

    private void insertNationwideStores(int storeCount, Long storeCategoryId, Profile profile) {
        int nearby = profile.localCandidateCount(storeCount);
        jdbcTemplate.update("""
                with numbered_users as (
                    select id, row_number() over (order by id) as row_number
                    from users where email like ? || '%%' || ? limit ?
                )
                insert into stores (created_at, updated_at, owner_id, store_category_id, store_name,
                    representative_name, representative_phone, business_owner_name, business_number,
                    telecom_sales_report_number, postal_code, address_line1, location, settlement_bank_name,
                    settlement_bank_account, settlement_account_holder, review_count, status,
                    is_delivery_available, is_active, commission_rate)
                select now(), now(), id, ?, ? || row_number, '검색 인덱스 대표자', '01000000000',
                    '검색 인덱스 대표자', lpad(row_number::text, 12, '0'), 'search-index-report-' || row_number,
                    '06134', '검색 인덱스 주소',
                    ST_SetSRID(ST_MakePoint(
                        case when row_number <= ? then ? + ((row_number * 53 %% 101) - 50) * 0.0002
                             else (%s) end,
                        case when row_number <= ? then ? + ((row_number * 97 %% 101) - 50) * 0.0002
                             else (%s) end), 4326)::geography,
                    '검색은행', '110-000-000000', '검색 인덱스 대표자', 0, 'APPROVED', true, 'ACTIVE', 5.00
                from numbered_users order by row_number
                """.formatted(regionCoordinateCase(true), regionCoordinateCase(false)),
                USER_EMAIL_PREFIX, USER_EMAIL_SUFFIX, storeCount, storeCategoryId, STORE_PREFIX,
                nearby, CENTER_LONGITUDE, nearby, CENTER_LATITUDE);
    }

    private String regionCoordinateCase(boolean longitude) {
        StringBuilder result = new StringBuilder("case");
        int upper = 0;
        for (Region region : REGIONS) {
            upper += region.weight();
            double coordinate = longitude ? region.longitude() : region.latitude();
            int multiplier = longitude ? 53 : 97;
            result.append(" when mod(row_number - 1, 1000) < ").append(upper).append(" then ")
                    .append(coordinate).append(" + (mod(row_number * ").append(multiplier)
                    .append(", 1001) - 500) * 0.00008");
        }
        return result.append(" end").toString();
    }

    private void insertCitywideStores(int storeCount, Long storeCategoryId) {
        jdbcTemplate.update("""
                with numbered_users as (
                    select id, row_number() over (order by id) as row_number
                    from users
                    where email like ? || '%' || ?
                    limit ?
                )
                insert into stores (created_at, updated_at, owner_id, store_category_id, store_name,
                                    representative_name, representative_phone, business_owner_name,
                                    business_number, telecom_sales_report_number, postal_code, address_line1,
                                    location, settlement_bank_name, settlement_bank_account,
                                    settlement_account_holder, review_count, status, is_delivery_available,
                                    is_active, commission_rate)
                select now(), now(), id, ?, ? || row_number,
                       '검색 인덱스 대표자', '01000000000', '검색 인덱스 대표자',
                       lpad(row_number::text, 12, '0'), 'search-index-report-' || row_number,
                       '06134', '검색 인덱스 주소',
                       ST_SetSRID(ST_MakePoint(
                           ? + (((row_number - 1) % 100) / 99.0 - 0.5) * 0.34,
                           ? + (((row_number - 1) / 100)::double precision
                                / (ceil(? / 100.0) - 1) - 0.5) * 0.18), 4326)::geography,
                       '검색은행', '110-000-000000', '검색 인덱스 대표자',
                       0, 'APPROVED', true, 'ACTIVE', 5.00
                from numbered_users
                order by id
                """, USER_EMAIL_PREFIX, USER_EMAIL_SUFFIX, storeCount, storeCategoryId, STORE_PREFIX,
                CENTER_LONGITUDE, CENTER_LATITUDE, storeCount);
    }

    private void insertProducts(Long productCategoryId, Profile profile) {
        jdbcTemplate.update("""
                insert into products (created_at, updated_at, store_id, category_id, product_name,
                                      price, stock, is_active, order_count)
                select now(), now(), s.id, ?,
                       ? || %s || '-' || row_number() over (order by s.id),
                       1000, 100, true, 0
                from stores s
                where s.store_name like ?
                """.formatted(profile.keywordExpression()), productCategoryId, PRODUCT_PREFIX, STORE_PREFIX + "%");
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
                          double centerLatitude, String keyword, String specificKeyword, String shortKeyword,
                          short todayDayOfWeek) {
    }

    public enum Profile {
        BROAD(5, 3, "case when ((row_number() over (order by s.id) * 7919 + 17) % 10000) < 2000 "
                + "then '일반검색어 두글' else '검색비매칭' end"),
        CITYWIDE(0, 0, "case when ((row_number() over (order by s.id) * 7919 + 17) % 10000) < 200 "
                + "then '일반검색어 희소검색어' when ((row_number() over (order by s.id) * 7919 + 17) % 10000) < 2000 "
                + "then '일반검색어' else '검색비매칭' end"),
        NATIONWIDE_NORMAL(0, 0, "case when ((row_number() over (order by s.id) * 7919 + 17) % 10000) < 200 "
                + "then '일반검색어 희소검색어' when ((row_number() over (order by s.id) * 7919 + 17) % 10000) < 2000 "
                + "then '일반검색어 두글' else '검색비매칭' end"),
        NATIONWIDE_DENSE(0, 0, "case when ((row_number() over (order by s.id) * 7919 + 17) % 10000) < 200 "
                + "then '일반검색어 희소검색어' when ((row_number() over (order by s.id) * 7919 + 17) % 10000) < 2000 "
                + "then '일반검색어 두글' else '검색비매칭' end");

        private final int spatialModulo;
        private final int nearbyRows;
        private final String keywordExpression;

        Profile(int spatialModulo, int nearbyRows, String keywordExpression) {
            this.spatialModulo = spatialModulo;
            this.nearbyRows = nearbyRows;
            this.keywordExpression = keywordExpression;
        }

        int spatialModulo() {
            return spatialModulo;
        }

        int nearbyRows() {
            return nearbyRows;
        }

        String keywordExpression() {
            return keywordExpression;
        }

        boolean nationwide() {
            return this == NATIONWIDE_NORMAL || this == NATIONWIDE_DENSE;
        }

        int localCandidateCount(int storeCount) {
            return this == NATIONWIDE_DENSE ? storeCount / 50 : storeCount / 100;
        }
    }

    private record Region(int weight, double longitude, double latitude) {
    }
}
