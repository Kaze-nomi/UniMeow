CREATE TABLE universities (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(255) UNIQUE NOT NULL,
    short_name VARCHAR(50)  UNIQUE NOT NULL,
    subdomain  VARCHAR(50)  UNIQUE NOT NULL,
    icon_url   VARCHAR(500),
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE university_domains (
    id            BIGSERIAL    PRIMARY KEY,
    domain        VARCHAR(255) UNIQUE NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('STUDENT', 'EMPLOYEE')),
    university_id BIGINT       NOT NULL REFERENCES universities(id)
);

CREATE INDEX idx_university_domains_university ON university_domains(university_id);

CREATE TABLE university_faculties (
    id            BIGSERIAL    PRIMARY KEY,
    university_id BIGINT       NOT NULL REFERENCES universities(id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    short_name    VARCHAR(50)  NOT NULL,
    UNIQUE (university_id, short_name)
);

CREATE TABLE university_programs (
    id            BIGSERIAL    PRIMARY KEY,
    university_id BIGINT       NOT NULL REFERENCES universities(id) ON DELETE CASCADE,
    faculty_id    BIGINT       NOT NULL REFERENCES university_faculties(id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    short_name    VARCHAR(50)  NOT NULL,
    UNIQUE (faculty_id, short_name)
);

CREATE INDEX idx_university_programs_university ON university_programs(university_id);

CREATE TABLE users (
    id                   UUID          PRIMARY KEY,
    email_google         VARCHAR(255)  UNIQUE NOT NULL,
    username             VARCHAR(50)   UNIQUE,
    name                 VARCHAR(255)  NOT NULL,
    surname              VARCHAR(255),
    email_university     VARCHAR(255)  UNIQUE,
    avatar_url           TEXT,
    cover_url            VARCHAR(2048),
    status               VARCHAR(300),
    bio                  VARCHAR(500),
    university_id        BIGINT        REFERENCES universities(id),
    faculty_id           BIGINT        REFERENCES university_faculties(id),
    program_id           BIGINT        REFERENCES university_programs(id),
    course               SMALLINT,
    education_level      VARCHAR(20)   CHECK (education_level IN ('BACHELOR','MASTER','PHD','SPECIALIST')),
    graduation_year      SMALLINT,
    is_student_verified  BOOLEAN       NOT NULL DEFAULT FALSE,
    is_employee_verified BOOLEAN       NOT NULL DEFAULT FALSE,
    is_admin             BOOLEAN       NOT NULL DEFAULT FALSE,
    banned_until         TIMESTAMP,
    ban_reason           VARCHAR(500),
    created_at           TIMESTAMP     NOT NULL
);

CREATE INDEX idx_users_university ON users(university_id);
CREATE INDEX idx_users_faculty    ON users(faculty_id);
CREATE INDEX idx_users_program    ON users(program_id);
CREATE INDEX idx_users_banned_until ON users(banned_until) WHERE banned_until IS NOT NULL;
