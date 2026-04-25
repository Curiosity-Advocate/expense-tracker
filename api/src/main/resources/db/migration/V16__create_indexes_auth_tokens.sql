CREATE INDEX idx_revoked_tokens_jti
    ON revoked_tokens(token_jti);

CREATE INDEX idx_revoked_tokens_expires
    ON revoked_tokens(expires_at);