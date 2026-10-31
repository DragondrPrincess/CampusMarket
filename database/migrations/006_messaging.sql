USE campus_market;
CREATE TABLE IF NOT EXISTS conversations (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 product_id BIGINT NOT NULL,
 buyer_id BIGINT NOT NULL,
 seller_id BIGINT NOT NULL,
 product_title VARCHAR(100) NOT NULL,
 last_seq BIGINT NOT NULL DEFAULT 0,
 buyer_read_seq BIGINT NOT NULL DEFAULT 0,
 seller_read_seq BIGINT NOT NULL DEFAULT 0,
 last_message_preview VARCHAR(1000) NOT NULL DEFAULT '',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_conversation_product_buyer(product_id,buyer_id),
 INDEX idx_conversation_buyer(buyer_id,updated_at,id),
 INDEX idx_conversation_seller(seller_id,updated_at,id),
 FOREIGN KEY(product_id) REFERENCES products(id),
 FOREIGN KEY(buyer_id) REFERENCES users(id),
 FOREIGN KEY(seller_id) REFERENCES users(id),
 CONSTRAINT chk_conversation_parties CHECK(buyer_id<>seller_id),
 CONSTRAINT chk_conversation_reads CHECK(last_seq>=0 AND buyer_read_seq BETWEEN 0 AND last_seq AND seller_read_seq BETWEEN 0 AND last_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS messages (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 conversation_id BIGINT NOT NULL,
 seq BIGINT NOT NULL,
 sender_id BIGINT NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_message_seq(conversation_id,seq),
 UNIQUE KEY uq_message_request(conversation_id,sender_id,request_id),
 FOREIGN KEY(conversation_id) REFERENCES conversations(id),
 FOREIGN KEY(sender_id) REFERENCES users(id),
 CONSTRAINT chk_message_seq CHECK(seq>0),
 CONSTRAINT chk_message_length CHECK(CHAR_LENGTH(content) BETWEEN 1 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
UPDATE app_metadata SET schema_version=GREATEST(schema_version,6) WHERE id=1;
