-- NULL preserves the original chat prompt; historical games and prompts are unchanged.
ALTER TABLE tournaments ADD COLUMN banter_level VARCHAR(16);
ALTER TABLE tournaments ADD CONSTRAINT chk_tournaments_banter_level
    CHECK (banter_level IN ('POLITE', 'PLAYFUL', 'SPICY'));
