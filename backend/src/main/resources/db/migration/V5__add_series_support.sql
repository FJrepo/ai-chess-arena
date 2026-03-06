ALTER TABLE tournaments
    ADD COLUMN IF NOT EXISTS matchup_best_of INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS finals_best_of INT;

ALTER TABLE games
    ADD COLUMN IF NOT EXISTS series_id UUID,
    ADD COLUMN IF NOT EXISTS series_game_number INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS series_best_of INT NOT NULL DEFAULT 1;

UPDATE games
SET series_id = COALESCE(series_id, id),
    series_game_number = 1,
    series_best_of = 1
WHERE series_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_games_series_id ON games(series_id);
