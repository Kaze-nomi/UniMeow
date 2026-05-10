CREATE TABLE program_proposals (
    id                   BIGSERIAL    PRIMARY KEY,
    author_id            UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    university_id        BIGINT       NOT NULL REFERENCES universities(id) ON DELETE CASCADE,
    faculty_id           BIGINT       NOT NULL REFERENCES university_faculties(id) ON DELETE CASCADE,
    faculty_name         VARCHAR(255) NOT NULL,
    faculty_short_name   VARCHAR(50)  NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    short_name           VARCHAR(50)  NOT NULL,
    status               VARCHAR(32)  NOT NULL DEFAULT 'NEW',
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    reviewed_by          UUID         REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at          TIMESTAMP
);

CREATE INDEX idx_program_proposals_status_created ON program_proposals(status, created_at DESC);
CREATE INDEX idx_program_proposals_author_created ON program_proposals(author_id, created_at DESC);
CREATE INDEX idx_program_proposals_university     ON program_proposals(university_id);
CREATE INDEX idx_program_proposals_faculty        ON program_proposals(faculty_id);
CREATE INDEX idx_program_proposals_reviewed_by    ON program_proposals(reviewed_by);
