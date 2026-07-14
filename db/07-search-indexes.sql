CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stores_location_gist
    ON stores USING GIST (location);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_products_lower_name_trgm_gin
    ON products USING GIN (lower(product_name) gin_trgm_ops);
