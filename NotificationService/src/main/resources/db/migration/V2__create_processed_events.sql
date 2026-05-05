CREATE TABLE processed_events (
    event_id   VARCHAR(100) PRIMARY KEY,
    processed_at TIMESTAMP  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_processed_events_time ON processed_events(processed_at);
