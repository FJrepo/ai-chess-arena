ALTER TABLE tournaments ADD COLUMN benchmark_definition JSONB;

ALTER TABLE games ADD COLUMN benchmark_pair_id UUID;
ALTER TABLE games DROP CONSTRAINT stockfish_practice_settings;

ALTER TABLE games ADD CONSTRAINT stockfish_settings CHECK (
    CASE WHEN practice_kind = 'HUMAN_STOCKFISH' OR benchmark_pair_id IS NOT NULL
        THEN stockfish_elo IS NOT NULL AND stockfish_elo BETWEEN 1320 AND 3190
        ELSE stockfish_elo IS NULL END
);

ALTER TABLE games ADD CONSTRAINT benchmark_game_settings CHECK (
    benchmark_pair_id IS NULL OR (
        tournament_id IS NOT NULL AND practice_kind IS NULL AND opening_id IS NOT NULL
        AND bracket_round IS NULL AND bracket_position IS NULL AND series_id IS NULL
        AND white_participant_id IS NULL AND black_participant_id IS NULL
        AND (white_model_id IS NULL) <> (black_model_id IS NULL)
    )
);

CREATE UNIQUE INDEX idx_benchmark_pair_color ON games (benchmark_pair_id, (white_model_id IS NOT NULL))
    WHERE benchmark_pair_id IS NOT NULL;
