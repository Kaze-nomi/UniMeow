CREATE TABLE subscriptions (
    subscriber_id  UUID      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_user_id UUID      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at     TIMESTAMP NOT NULL,
    PRIMARY KEY (subscriber_id, target_user_id)
);

CREATE INDEX idx_subscriptions_subscriber_created ON subscriptions(subscriber_id, created_at DESC);
CREATE INDEX idx_subscriptions_target_created     ON subscriptions(target_user_id, created_at DESC);
