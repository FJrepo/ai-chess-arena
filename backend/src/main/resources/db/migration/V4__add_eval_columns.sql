-- Add evaluation columns to moves table
ALTER TABLE moves ADD COLUMN evaluation_cp INTEGER;
ALTER TABLE moves ADD COLUMN evaluation_mate INTEGER;

-- Add comment for clarity
COMMENT ON COLUMN moves.evaluation_cp IS 'Stockfish evaluation in centipawns (from white perspective)';
COMMENT ON COLUMN moves.evaluation_mate IS 'Stockfish evaluation in moves to mate (positive for white, negative for black)';
