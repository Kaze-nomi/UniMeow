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
