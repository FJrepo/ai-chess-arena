ALTER TABLE tournaments
    DROP CONSTRAINT chk_tournaments_banter_level,
    ADD CONSTRAINT chk_tournaments_banter_level
        CHECK (banter_level IN ('POLITE', 'PLAYFUL', 'SPICY', 'MIND_GAMES'));
