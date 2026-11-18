ALTER TABLE games
    ADD COLUMN practice_kind VARCHAR(32),
    ADD COLUMN white_practice_control VARCHAR(16),
    ADD COLUMN black_practice_control VARCHAR(16),
    ADD COLUMN practice_source_game_id UUID REFERENCES games(id) ON DELETE SET NULL,
    ADD COLUMN practice_source_ply INTEGER CHECK (practice_source_ply >= 0),
    ADD COLUMN practice_request_id UUID,
    ADD CONSTRAINT practice_games_standalone CHECK (practice_kind IS NULL OR tournament_id IS NULL),
    ADD CONSTRAINT practice_game_sides CHECK (practice_kind IS NULL OR
        (white_practice_control IS NOT NULL AND black_practice_control IS NOT NULL)),
    ADD CONSTRAINT practice_source_request_unique UNIQUE (practice_source_game_id, practice_request_id);

CREATE INDEX idx_practice_games_created ON games (created_at DESC, id) WHERE practice_kind IS NOT NULL;

ALTER TABLE moves ADD COLUMN is_inherited BOOLEAN NOT NULL DEFAULT FALSE;
