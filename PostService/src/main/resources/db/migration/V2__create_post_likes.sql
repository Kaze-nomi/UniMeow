CREATE TABLE post_likes (
    post_id    UUID      NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id    UUID      NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (post_id, user_id)
);

CREATE INDEX idx_likes_user_id ON post_likes(user_id);