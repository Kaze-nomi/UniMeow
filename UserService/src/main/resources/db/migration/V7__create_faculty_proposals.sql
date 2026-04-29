CREATE TABLE faculty_proposals (
    id            BIGSERIAL    PRIMARY KEY,
    author_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    university_id BIGINT       NOT NULL REFERENCES universities(id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    short_name    VARCHAR(50)  NOT NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'NEW',
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    reviewed_by   UUID         REFERENCES users(id),
    reviewed_at   TIMESTAMP
);
