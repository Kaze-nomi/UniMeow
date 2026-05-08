CREATE TABLE banned_google_accounts (
    email_google VARCHAR(255) PRIMARY KEY,
    reason       VARCHAR(500),
    moderator_id UUID,
    banned_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
