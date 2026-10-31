USE campus_market;
CREATE TABLE IF NOT EXISTS product_images (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 uploader_id BIGINT NOT NULL,
 product_id BIGINT NULL,
 storage_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 media_type VARCHAR(32) NOT NULL,
 file_size INT NOT NULL,
 width INT NOT NULL,
 height INT NOT NULL,
 position INT NOT NULL DEFAULT 0,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY (uploader_id) REFERENCES users(id),
 FOREIGN KEY (product_id) REFERENCES products(id),
 INDEX idx_images_product(product_id,active,position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS favorites (
 user_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(user_id,product_id),
 FOREIGN KEY(user_id) REFERENCES users(id),
 FOREIGN KEY(product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
UPDATE app_metadata SET schema_version=GREATEST(schema_version,4) WHERE id=1;
