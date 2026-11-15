-- Human-readable reason shown while a game is paused for an operational problem
-- (for example, OpenRouter rejected the API key). Cleared when the game is started again.
ALTER TABLE games
    ADD COLUMN IF NOT EXISTS status_detail VARCHAR(500);

-- One persisted move per ply. Databases that already contain duplicate plies from
-- concurrent writes keep migrating; the application-level row lock still applies.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM moves
        GROUP BY game_id, move_number, color
        HAVING COUNT(*) > 1
    ) THEN
        RAISE NOTICE 'Skipping uq_moves_game_ply: duplicate plies exist in moves';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS uq_moves_game_ply ON moves (game_id, move_number, color);
    END IF;
END
$$;
