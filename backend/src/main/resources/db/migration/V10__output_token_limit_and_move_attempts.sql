-- Per-tournament cap on generated tokens per model answer (OpenRouter max_tokens, includes reasoning tokens).
ALTER TABLE tournaments
    ADD COLUMN IF NOT EXISTS max_output_tokens INT NOT NULL DEFAULT 4096;

-- Every model attempt per ply, including failed ones, so their tokens, cost and content are not lost.
CREATE TABLE IF NOT EXISTS move_attempts (
    id                UUID PRIMARY KEY,
    game_id           UUID         NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    move_number       INT          NOT NULL,
    color             VARCHAR(10)  NOT NULL,
    attempt_number    INT          NOT NULL,
    model_id          VARCHAR(255),
    outcome           VARCHAR(32)  NOT NULL,
    proposed_move     VARCHAR(64),
    prompt_tokens     INT,
    completion_tokens INT,
    reasoning_tokens  INT,
    cost_usd          NUMERIC(12, 8),
    response_time_ms  BIGINT,
    finish_reason     VARCHAR(32),
    error_message     VARCHAR(500),
    raw_response      TEXT,
    created_at        TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_move_attempts_game_ply ON move_attempts (game_id, move_number, color);
