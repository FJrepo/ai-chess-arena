-- Existing games retain their position, history and result. No opening backfill.
ALTER TABLE tournaments
    ADD COLUMN opening_mode VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN opening_id VARCHAR(64),
    ADD CONSTRAINT ck_tournament_opening CHECK (
        (opening_mode = 'FIXED' AND opening_id IS NOT NULL)
        OR (opening_mode IN ('STANDARD', 'RANDOM_BOOK') AND opening_id IS NULL)
    );
ALTER TABLE games
    ADD COLUMN opening_id VARCHAR(64),
    ADD COLUMN opening_name VARCHAR(120),
    ADD COLUMN opening_eco VARCHAR(3);
ALTER TABLE moves ADD COLUMN is_book BOOLEAN NOT NULL DEFAULT FALSE;
