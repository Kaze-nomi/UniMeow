CREATE TABLE refresh_sessions (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token VARCHAR(255) UNIQUE NOT NULL,
    expires_at    TIMESTAMP    NOT NULL
);

CREATE INDEX idx_refresh_sessions_token ON refresh_sessions(refresh_token);