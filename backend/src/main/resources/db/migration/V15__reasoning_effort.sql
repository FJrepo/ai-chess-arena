-- Reasoning effort (OpenRouter reasoning.effort): per tournament, optionally overridden per participant. NULL on the
-- tournament means "model default" (nothing is sent); NULL on a participant means "use the tournament's". A change
-- applies to running games from their next turn.
ALTER TABLE tournaments
    ADD COLUMN IF NOT EXISTS reasoning_effort VARCHAR(16);

ALTER TABLE tournaments
    ADD CONSTRAINT chk_tournaments_reasoning_effort
        CHECK (reasoning_effort IN ('none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'));

ALTER TABLE tournament_participants
    ADD COLUMN IF NOT EXISTS reasoning_effort VARCHAR(16);

ALTER TABLE tournament_participants
    ADD CONSTRAINT chk_tournament_participants_reasoning_effort
        CHECK (reasoning_effort IN ('none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'));

-- The level actually sent with each attempt (after mapping to a level the model supports); NULL when none was sent,
-- and for every attempt recorded before this migration.
ALTER TABLE move_attempts
    ADD COLUMN IF NOT EXISTS reasoning_effort VARCHAR(16);

ALTER TABLE move_attempts
    ADD CONSTRAINT chk_move_attempts_reasoning_effort
        CHECK (reasoning_effort IN ('none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'));
