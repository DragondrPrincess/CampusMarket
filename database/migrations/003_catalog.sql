-- Additive phase 4 migration; no existing data or credentials are removed.
USE campus_market;
CREATE TABLE IF NOT EXISTS categories (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 name VARCHAR(40) NOT NULL,
 sort_order INT NOT NULL DEFAULT 0,
 enabled BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS products (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 seller_id BIGINT NOT NULL,
 category_id BIGINT NOT NULL,
 title VARCHAR(100) NOT NULL,
 description VARCHAR(2000) NOT NULL,
 price DECIMAL(10,2) NOT NULL,
 condition_label VARCHAR(30) NOT NULL,
 trade_location VARCHAR(120) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'ON_SALE',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_products_seller FOREIGN KEY(seller_id) REFERENCES users(id),
 CONSTRAINT fk_products_category FOREIGN KEY(category_id) REFERENCES categories(id),
 CONSTRAINT ck_products_price CHECK(price >= 0),
 INDEX idx_products_category_status(category_id,status,created_at,id),
 INDEX idx_products_status(status,created_at,id),
 INDEX idx_products_seller(seller_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO categories(code,name,sort_order) SELECT 'books','教材书籍',10 WHERE NOT EXISTS(SELECT 1 FROM categories WHERE code='books');
INSERT INTO categories(code,name,sort_order) SELECT 'digital','数码电子',20 WHERE NOT EXISTS(SELECT 1 FROM categories WHERE code='digital');
INSERT INTO categories(code,name,sort_order) SELECT 'dorm','宿舍生活',30 WHERE NOT EXISTS(SELECT 1 FROM categories WHERE code='dorm');
INSERT INTO categories(code,name,sort_order) SELECT 'sports','运动户外',40 WHERE NOT EXISTS(SELECT 1 FROM categories WHERE code='sports');
INSERT INTO categories(code,name,sort_order) SELECT 'clothing','服饰配件',50 WHERE NOT EXISTS(SELECT 1 FROM categories WHERE code='clothing');
INSERT INTO categories(code,name,sort_order) SELECT 'other','其他好物',60 WHERE NOT EXISTS(SELECT 1 FROM categories WHERE code='other');
UPDATE app_metadata SET schema_version=GREATEST(schema_version,3) WHERE id=1;
