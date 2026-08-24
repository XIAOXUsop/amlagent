ALTER TABLE customer DROP INDEX uk_customer_id_card;
ALTER TABLE customer MODIFY COLUMN id_card VARCHAR(512) NOT NULL;
ALTER TABLE customer ADD COLUMN id_card_fingerprint CHAR(64) NULL AFTER id_card;
ALTER TABLE customer ADD UNIQUE KEY uk_customer_id_card_fingerprint (id_card_fingerprint);
