ALTER TABLE tournament_participants
    ADD COLUMN IF NOT EXISTS control_type VARCHAR(20);

UPDATE tournament_participants
SET control_type = 'AI'
WHERE control_type IS NULL;

ALTER TABLE tournament_participants
    ALTER COLUMN control_type SET NOT NULL;

ALTER TABLE tournament_participants
    ALTER COLUMN control_type SET DEFAULT 'AI';

ALTER TABLE tournament_participants
    ALTER COLUMN model_id DROP NOT NULL;

ALTER TABLE chat_messages
    ALTER COLUMN sender_model DROP NOT NULL;
