-- Row Level Security enforced at the PostgreSQL layer.
-- This is the third and most important defence layer:
--   Layer 1: application always passes userId in service method signatures
--   Layer 2: repository WHERE clauses filter by userId
--   Layer 3: this file — PostgreSQL rejects any query that would
--             return another user's rows regardless of what the
--             application sent. A bug in layers 1 or 2 produces
--             an empty result set, never a data leak.
--
-- The session variable app.current_user_id is set by the Hibernate
-- RLS interceptor before every query. Every policy reads from it.

-- Enable RLS on all tenant-scoped tables
ALTER TABLE expenses                ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_categories      ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_targets         ENABLE ROW LEVEL SECURITY;
ALTER TABLE categories              ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_suggestions          ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_grants           ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_profiles           ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_queue               ENABLE ROW LEVEL SECURITY;
ALTER TABLE dead_letter_jobs        ENABLE ROW LEVEL SECURITY;

-- expenses
CREATE POLICY user_isolation ON expenses
    USING (user_id = current_setting('app.current_user_id')::uuid);

-- expense_categories has no user_id column — it inherits isolation
-- through the expense it belongs to. We enforce this by joining
-- back to expenses rather than duplicating user_id here.
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

-- categories: user sees their own private categories AND all system categories
CREATE POLICY user_isolation ON categories
    USING (
        user_id = current_setting('app.current_user_id')::uuid
        OR user_id IS NULL
    );

-- access_grants: grantor sees grants they created,
-- grantee sees grants that give them access
CREATE POLICY user_isolation ON access_grants
    USING (
        grantor_user_id = current_setting('app.current_user_id')::uuid
        OR grantee_user_id = current_setting('app.current_user_id')::uuid
    );

-- user_profiles
CREATE POLICY user_isolation ON user_profiles
    USING (user_id = current_setting('app.current_user_id')::uuid);

-- job_queue: NULL user_id covers system jobs (partition creation,
-- archival) that don't belong to any user — those must remain visible
-- to the worker regardless of the current user context.
CREATE POLICY user_isolation ON job_queue
    USING (
        user_id = current_setting('app.current_user_id')::uuid
        OR user_id IS NULL
    );

-- dead_letter_jobs
CREATE POLICY user_isolation ON dead_letter_jobs
    USING (
        user_id = current_setting('app.current_user_id')::uuid
        OR user_id IS NULL
    );