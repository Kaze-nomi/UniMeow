CREATE TABLE university_domains (
    id              BIGSERIAL    PRIMARY KEY,
    domain          VARCHAR(255) UNIQUE NOT NULL,
    role            VARCHAR(20)  NOT NULL CHECK (role IN ('STUDENT', 'EMPLOYEE')),
    university_name VARCHAR(255) NOT NULL
);

INSERT INTO university_domains (domain, role, university_name) VALUES
    ('edu.hse.ru',          'STUDENT',  'НИУ ВШЭ'),
    ('hse.ru',              'EMPLOYEE', 'НИУ ВШЭ');