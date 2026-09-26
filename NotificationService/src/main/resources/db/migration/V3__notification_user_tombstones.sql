-- Separate topics can deliver a post event after the account deletion event.
-- Retain removal state permanently so delayed/replayed events cannot recreate notices.
CREATE TABLE notification_deleted_users (
    user_id UUID PRIMARY KEY,
    deleted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
