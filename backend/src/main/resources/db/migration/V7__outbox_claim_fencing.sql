ALTER TABLE outbox_event ADD COLUMN claim_owner VARCHAR(64) NULL AFTER published_at;
ALTER TABLE outbox_event ADD COLUMN claim_version BIGINT NOT NULL DEFAULT 0 AFTER claim_owner;
ALTER TABLE outbox_event ADD KEY idx_outbox_status_claim (status, published_at, claim_version);
