-- A per-game counter that grows with every status change (start, pause, game over), written under the game's row
-- lock in the same transaction as the change. Live status updates carry it, so a client can drop an update that
-- arrives after a newer one (updates are sent after the commit, possibly out of order).
ALTER TABLE games
    ADD COLUMN IF NOT EXISTS status_revision BIGINT NOT NULL DEFAULT 0;
