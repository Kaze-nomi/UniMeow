CREATE TABLE comments (
    id                UUID      PRIMARY KEY,
    post_id           UUID      NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    author_id         UUID      NOT NULL,
    parent_comment_id UUID      REFERENCES comments(id) ON DELETE CASCADE,
    content           TEXT      NOT NULL,
    likes_count       INTEGER   NOT NULL DEFAULT 0,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL
);

CREATE INDEX idx_comments_post_id           ON comments(post_id, created_at ASC);
CREATE INDEX idx_comments_author_id         ON comments(author_id);
CREATE INDEX idx_comments_created_at        ON comments(created_at DESC);
CREATE INDEX idx_comments_parent_comment_id ON comments(parent_comment_id);
