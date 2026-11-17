-- Existing tournaments keep their current bracket/draft state; no generation backfill.
ALTER TABLE tournaments ADD COLUMN creation_draw_mode VARCHAR(16)
    CHECK (creation_draw_mode IN ('ORDER', 'RANDOM'));
