ALTER TABLE tournaments
    ADD COLUMN auto_run_status VARCHAR(16) NOT NULL DEFAULT 'OFF',
    ADD COLUMN max_concurrent_games INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN budget_usd NUMERIC(19, 2),
    ADD COLUMN auto_run_pause_reason VARCHAR(500),
    ADD CONSTRAINT ck_auto_run_status CHECK (auto_run_status IN ('OFF', 'RUNNING', 'PAUSED', 'FINISHED')),
    ADD CONSTRAINT ck_max_concurrent_games CHECK (max_concurrent_games BETWEEN 1 AND 8),
    ADD CONSTRAINT ck_budget_usd CHECK (budget_usd IS NULL OR budget_usd > 0);
