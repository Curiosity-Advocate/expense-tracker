-- Row Level Security enforced at the PostgreSQL layer.
-- Three-layer defence:
--   Layer 1: application always passes userId in service method signatures
--   Layer 2: repository WHERE clauses filter by userId
--   Layer 3: this file — PostgreSQL rejects any query that would
--             return another user's rows regardless of what the
--             application sent. A bug in layers 1 or 2 produces
--             an empty result set, never a data leak.
--
-- The session variable app.current_user_id is set by RlsSessionAspect
-- before every @Transactional method via SET LOCAL.
-- SET LOCAL scopes the variable to the current transaction only —
-- resets automatically when the transaction ends.

ALTER TABLE expenses                    ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_categories          ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_idempotency_keys    ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_targets             ENABLE ROW LEVEL SECURITY;
ALTER TABLE categories                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_grants               ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_profiles               ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_accounts               ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_queue                   ENABLE ROW LEVEL SECURITY;
ALTER TABLE dead_letter_jobs            ENABLE ROW LEVEL SECURITY;

-- expenses
CREATE POLICY user_isolation ON expenses
    USING (user_id = current_setting('app.current_user_id')::uuid);

-- expense_categories has no user_id column — isolation inherited
-- through the parent expense via a subquery.
CREATE POLICY user_isolation ON expense_categories
    USING (
        EXISTS (
            SELECT 1 FROM expenses e
            WHERE e.id = expense_id
              AND e.expense_date = expense_date
              AND e.user_id = current_setting('app.current_user_id')::uuid
        )
    );

-- expense_idempotency_keys
CREATE POLICY user_isolation ON expense_idempotency_keys
    USING (user_id = current_setting('app.current_user_id')::uuid);

-- expense_targets
CREATE POLICY user_isolation ON expense_targets
    USING (user_id = current_setting('app.current_user_id')::uuid);

-- categories: user sees their own private categories AND all system categories.
-- System categories have user_id = NULL.
CREATE POLICY user_isolation ON categories
    USING (
        user_id = current_setting('app.current_user_id')::uuid
        OR user_id IS NULL
    );

-- access_grants: grantor sees grants they created,
-- grantee sees grants that give them access.
CREATE POLICY user_isolation ON access_grants
    USING (
        grantor_user_id = current_setting('app.current_user_id')::uuid
        OR grantee_user_id = current_setting('app.current_user_id')::uuid
    );

-- user_profiles
CREATE POLICY user_isolation ON user_profiles
    USING (user_id = current_setting('app.current_user_id')::uuid);

-- bank_accounts
CREATE POLICY user_isolation ON bank_accounts
    USING (user_id = current_setting('app.current_user_id')::uuid);

-- job_queue: NULL user_id covers system jobs (partition creation, archival)
-- that don't belong to any user — visible to worker regardless of user context.
CREATE POLICY user_isolation ON job_queue
    USING (
        user_id = current_setting('app.current_user_id')::uuid
        OR user_id IS NULL
    );

-- dead_letter_jobs: same NULL user_id logic as job_queue.
CREATE POLICY user_isolation ON dead_letter_jobs
    USING (
        user_id = current_setting('app.current_user_id')::uuid
        OR user_id IS NULL
    );