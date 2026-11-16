-- Per tournament: whether models may resign instead of moving (from their 10th move on). Off by default, so every
-- game is played to the end as before. A change applies to running games from their next turn.
ALTER TABLE tournaments
    ADD COLUMN IF NOT EXISTS resignation_allowed BOOLEAN NOT NULL DEFAULT FALSE;
