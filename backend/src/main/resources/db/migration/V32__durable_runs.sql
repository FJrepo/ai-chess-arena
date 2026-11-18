-- 2.0 durable runs: which game loop owns a game (a fencing token with a lease) and whether a model request was sent
-- without its answer being recorded yet. Startup recovery resumes never-sent work and pauses a game whose request was
-- in flight, so a possibly charged request is never sent twice.
ALTER TABLE games
    ADD COLUMN run_owner UUID,
    ADD COLUMN run_lease_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN request_sent_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_games_run_lease CHECK ((run_owner IS NULL) = (run_lease_until IS NULL));

-- The previous version kept no request record, so for a game it left in progress it is unknown whether a model
-- request was in flight. Pause those games and their runs exactly as the previous version's restart recovery did;
-- durable resume applies from the next restart on.
UPDATE games
SET status = 'PAUSED',
    status_detail = 'Paused by the upgrade to durable runs: a model request may have been in flight. Check spend, then resume.',
    status_revision = status_revision + 1,
    live_revision = live_revision + 1,
    turn_started_at = NULL,
    turn_deadline_at = NULL,
    turn_chat_mode = NULL,
    turn_banter_level = NULL
WHERE status = 'IN_PROGRESS';

UPDATE tournaments
SET auto_run_status = 'PAUSED',
    auto_run_pause_reason = 'Backend restarted',
    updated_at = NOW()
WHERE auto_run_status = 'RUNNING';

CREATE INDEX idx_games_run_lease ON games (run_lease_until) WHERE run_owner IS NOT NULL;
