-- ── expenses ──────────────────────────────────────────────────────────────────
-- Primary query patterns: filter by user + date range, and user + merchant.
CREATE INDEX idx_expenses_user_date
    ON expenses (user_id, expense_date DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_expenses_user_merchant
    ON expenses (user_id, merchant_name)
    WHERE deleted_at IS NULL;

-- ── expense_categories ────────────────────────────────────────────────────────
-- Lookup all categories for a given expense (used in toDomain mapping).
CREATE INDEX idx_expense_categories_expense
    ON expense_categories (expense_id, expense_date);

-- ── revoked_tokens ────────────────────────────────────────────────────────────
-- Already indexed in V2 — no additional indexes needed here.

-- ── users ─────────────────────────────────────────────────────────────────────
-- Username lookup on every login.
CREATE INDEX idx_users_username ON users (username);
CREATE INDEX idx_users_email    ON users (email);
