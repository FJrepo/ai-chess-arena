ALTER TABLE tournaments
    ADD COLUMN IF NOT EXISTS shared_custom_instructions TEXT;

ALTER TABLE tournament_participants
    ADD COLUMN IF NOT EXISTS custom_instructions TEXT;
