ALTER TABLE games ADD COLUMN stockfish_elo INTEGER,
    ADD CONSTRAINT stockfish_practice_settings CHECK (
        (practice_kind IS DISTINCT FROM 'HUMAN_STOCKFISH' AND stockfish_elo IS NULL)
        OR (practice_kind = 'HUMAN_STOCKFISH' AND stockfish_elo BETWEEN 1320 AND 3190
            AND stockfish_elo IS NOT NULL)
    );
