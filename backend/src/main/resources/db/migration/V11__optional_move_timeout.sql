-- The move time limit is optional: NULL means no time limit per move. Existing tournaments keep their values.
ALTER TABLE tournaments
    ALTER COLUMN move_timeout_seconds DROP NOT NULL;
