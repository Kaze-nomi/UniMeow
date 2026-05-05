CREATE TABLE improvement_suggestions (
    id         BIGSERIAL     PRIMARY KEY,
    author_id  UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    text       VARCHAR(2000) NOT NULL,
    status     VARCHAR(32)   NOT NULL DEFAULT 'NEW',
    created_at TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE university_proposals (
    id              BIGSERIAL     PRIMARY KEY,
    author_id       UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(255)  NOT NULL,
    short_name      VARCHAR(50)   NOT NULL,
    subdomain       VARCHAR(50)   NOT NULL,
    student_domain  VARCHAR(255)  NOT NULL,
    employee_domain VARCHAR(255)  NOT NULL,
    city            VARCHAR(255),
    description     VARCHAR(2000),
    icon_url        VARCHAR(500),
    status          VARCHAR(32)   NOT NULL DEFAULT 'NEW',
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    reviewed_by     UUID          REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at     TIMESTAMP
);
