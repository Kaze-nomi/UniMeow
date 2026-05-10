CREATE TABLE banned_google_accounts (
    email_google VARCHAR(255) PRIMARY KEY,
    reason       VARCHAR(500),
    moderator_id UUID,
    banned_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_banned_google_accounts_moderator ON banned_google_accounts(moderator_id);
CREATE INDEX idx_banned_google_accounts_banned_at ON banned_google_accounts(banned_at DESC);
