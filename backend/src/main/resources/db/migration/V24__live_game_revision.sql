ALTER TABLE games ADD COLUMN live_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN turn_started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN turn_deadline_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE move_attempts ADD COLUMN turn_status_revision BIGINT;
