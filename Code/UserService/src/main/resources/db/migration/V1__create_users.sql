CREATE TABLE users (
    id                   UUID         PRIMARY KEY,
    email_google         VARCHAR(255) UNIQUE NOT NULL,
    username             VARCHAR(50)  UNIQUE,
    name                 VARCHAR(255) NOT NULL,
    surname              VARCHAR(255),
    patronymic           VARCHAR(255),
    email_university     VARCHAR(255) UNIQUE,
    avatar_url           TEXT,
    status               VARCHAR(300),
    is_student_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    is_employee_verified BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP    NOT NULL
);