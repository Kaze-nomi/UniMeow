CREATE TABLE idempotency_keys (
    key         VARCHAR(64) PRIMARY KEY,
    entity_id   VARCHAR(36) NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_idempotency_keys_created ON idempotency_keys(created_at);
