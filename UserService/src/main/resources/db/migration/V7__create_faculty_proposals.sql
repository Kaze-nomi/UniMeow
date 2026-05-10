CREATE TABLE faculty_proposals (
    id            BIGSERIAL    PRIMARY KEY,
    author_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    university_id BIGINT       NOT NULL REFERENCES universities(id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    short_name    VARCHAR(50)  NOT NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'NEW',
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    reviewed_by   UUID         REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at   TIMESTAMP
);

CREATE INDEX idx_faculty_proposals_status_created ON faculty_proposals(status, created_at DESC);
CREATE INDEX idx_faculty_proposals_author_created ON faculty_proposals(author_id, created_at DESC);
CREATE INDEX idx_faculty_proposals_university     ON faculty_proposals(university_id);
CREATE INDEX idx_faculty_proposals_reviewed_by    ON faculty_proposals(reviewed_by);
