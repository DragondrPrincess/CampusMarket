USE campus_market;
CREATE TABLE IF NOT EXISTS orders (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 product_id BIGINT NOT NULL,
 buyer_id BIGINT NOT NULL,
 seller_id BIGINT NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 product_title VARCHAR(100) NOT NULL,
 product_description VARCHAR(2000) NOT NULL,
 condition_label VARCHAR(30) NOT NULL,
 trade_location VARCHAR(120) NOT NULL,
 amount DECIMAL(10,2) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING_CONFIRMATION',
 active_product_id BIGINT GENERATED ALWAYS AS (CASE WHEN status <> 'CANCELLED' THEN product_id ELSE NULL END) STORED,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_order_request(buyer_id,request_id),
 UNIQUE KEY uq_order_product(active_product_id),
 INDEX idx_orders_buyer(buyer_id,created_at,id),
 INDEX idx_orders_seller(seller_id,created_at,id),
 FOREIGN KEY(product_id) REFERENCES products(id),
 FOREIGN KEY(buyer_id) REFERENCES users(id),
 FOREIGN KEY(seller_id) REFERENCES users(id),
 CONSTRAINT chk_order_parties CHECK(buyer_id<>seller_id),
 CONSTRAINT chk_order_amount CHECK(amount>=0),
 CONSTRAINT chk_order_status CHECK(status IN ('PENDING_CONFIRMATION','PENDING_PAYMENT','PAID','DELIVERED','COMPLETED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS simulated_payments (
 order_id BIGINT PRIMARY KEY,
 amount DECIMAL(10,2) NOT NULL,
 status VARCHAR(16) NOT NULL,
 paid_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 refunded_at TIMESTAMP(3) NULL,
 FOREIGN KEY(order_id) REFERENCES orders(id),
 CONSTRAINT chk_mock_payment_status CHECK(status IN ('SUCCEEDED','REFUNDED')),
 CONSTRAINT chk_mock_payment_amount CHECK(amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS order_events (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 order_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 action VARCHAR(24) NOT NULL,
 from_status VARCHAR(24) NULL,
 to_status VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_order_action(order_id,action),
 FOREIGN KEY(order_id) REFERENCES orders(id),
 FOREIGN KEY(actor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
UPDATE app_metadata SET schema_version=GREATEST(schema_version,5) WHERE id=1;
