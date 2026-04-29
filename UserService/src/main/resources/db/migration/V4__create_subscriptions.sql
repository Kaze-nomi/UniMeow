CREATE TABLE subscriptions (
    subscriber_id  UUID      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_user_id UUID      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at     TIMESTAMP NOT NULL,
    PRIMARY KEY (subscriber_id, target_user_id)
);

CREATE INDEX idx_subscriptions_target ON subscriptions(target_user_id);
