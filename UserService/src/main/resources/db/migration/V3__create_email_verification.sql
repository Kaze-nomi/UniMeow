CREATE TABLE email_verification_codes (
    user_id    UUID         PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    email      VARCHAR(255) NOT NULL,
    code       VARCHAR(10)  NOT NULL,
    attempts   INTEGER      NOT NULL DEFAULT 0,
    expires_at TIMESTAMP    NOT NULL
);
