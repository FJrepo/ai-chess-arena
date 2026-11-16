-- Chat mode per tournament: OFF (models stay silent), OPTIONAL (models may talk, the default) or MANDATORY (models
-- are asked for a short message with every move). Backfilled from the old switch: false -> OFF, true -> OPTIONAL.
-- trash_talk_enabled stays for now and is kept in sync by the application (false only for OFF) so an older version
-- still reads a sensible value after a downgrade; a later release drops it.
ALTER TABLE tournaments
    ADD COLUMN IF NOT EXISTS chat_mode VARCHAR(16) NOT NULL DEFAULT 'OPTIONAL';

UPDATE tournaments
SET chat_mode = CASE WHEN trash_talk_enabled THEN 'OPTIONAL' ELSE 'OFF' END;

ALTER TABLE tournaments
    ADD CONSTRAINT chk_tournaments_chat_mode CHECK (chat_mode IN ('OFF', 'OPTIONAL', 'MANDATORY'));

-- The chat mode that applied to each model move, for the talkativeness metric. NULL for human moves and overrides.
ALTER TABLE moves
    ADD COLUMN IF NOT EXISTS chat_mode VARCHAR(16);

-- Existing model moves: a silent prompt (version suffix -silent) means OFF; a stored message proves chat was on;
-- otherwise the tournament's current setting (games outside a tournament always allowed chat).
UPDATE moves m
SET chat_mode = CASE
        WHEN m.prompt_version LIKE '%-silent' THEN 'OFF'
        WHEN EXISTS (SELECT 1
                     FROM chat_messages c
                     WHERE c.game_id = m.game_id
                       AND c.move_number = m.move_number
                       AND c.sender_color = m.color) THEN 'OPTIONAL'
        ELSE COALESCE((SELECT t.chat_mode
                       FROM games g
                                JOIN tournaments t ON t.id = g.tournament_id
                       WHERE g.id = m.game_id), 'OPTIONAL')
    END
WHERE m.model_id IS NOT NULL
  AND NOT m.is_override;

ALTER TABLE moves
    ADD CONSTRAINT chk_moves_chat_mode CHECK (chat_mode IS NULL OR chat_mode IN ('OFF', 'OPTIONAL', 'MANDATORY'));

-- Analytics joins each model move to its message by game, move number and colour.
CREATE INDEX IF NOT EXISTS idx_chat_messages_game_ply ON chat_messages (game_id, move_number, sender_color);
