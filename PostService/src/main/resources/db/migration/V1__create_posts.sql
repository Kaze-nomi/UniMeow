CREATE TABLE posts (
    id              UUID      PRIMARY KEY,
    author_id       UUID      NOT NULL,
    content         TEXT      NOT NULL,
    media_urls      JSONB     NOT NULL DEFAULT '[]',
    likes_count     INTEGER   NOT NULL DEFAULT 0,
    comments_count  INTEGER   NOT NULL DEFAULT 0,
    university_id   BIGINT,
    faculty_id      BIGINT,
    program_id      BIGINT,
    topic_id        BIGINT,
    parent_topic_id BIGINT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_posts_author     ON posts(author_id, created_at DESC);
CREATE INDEX idx_posts_created_at ON posts(created_at DESC);
CREATE INDEX idx_posts_uni        ON posts(university_id, created_at DESC);
CREATE INDEX idx_posts_faculty    ON posts(faculty_id, created_at DESC);
CREATE INDEX idx_posts_program    ON posts(program_id, created_at DESC);
CREATE INDEX idx_posts_topic      ON posts(topic_id, created_at DESC);
CREATE INDEX idx_posts_parent     ON posts(parent_topic_id, created_at DESC);
