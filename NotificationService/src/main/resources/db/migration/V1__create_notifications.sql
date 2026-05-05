CREATE TABLE notifications (
    id               UUID        PRIMARY KEY,
    user_id          UUID        NOT NULL,
    actor_id         UUID        NOT NULL,
    type             VARCHAR(50) NOT NULL,
    entity_id        VARCHAR(36) NOT NULL,
    entity_type      VARCHAR(20) NOT NULL,
    parent_entity_id VARCHAR(36),
    is_read          BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_user_created ON notifications(user_id, created_at DESC);
CREATE INDEX idx_notifications_user_unread  ON notifications(user_id, is_read) WHERE is_read = FALSE;
