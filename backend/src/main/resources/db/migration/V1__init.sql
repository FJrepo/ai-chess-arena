CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE tournaments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    format VARCHAR(50) NOT NULL DEFAULT 'SINGLE_ELIMINATION',
    default_system_prompt TEXT,
    move_timeout_seconds INT NOT NULL DEFAULT 60,
    max_retries INT NOT NULL DEFAULT 3,
    trash_talk_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE tournament_participants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tournament_id UUID NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
    player_name VARCHAR(255) NOT NULL,
    model_id VARCHAR(255) NOT NULL,
    custom_system_prompt TEXT,
    seed INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_participants_tournament ON tournament_participants(tournament_id);

CREATE TABLE games (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tournament_id UUID REFERENCES tournaments(id) ON DELETE SET NULL,
    white_participant_id UUID REFERENCES tournament_participants(id),
    black_participant_id UUID REFERENCES tournament_participants(id),
    white_player_name VARCHAR(255),
    white_model_id VARCHAR(255),
    black_player_name VARCHAR(255),
    black_model_id VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'WAITING',
    result VARCHAR(50),
    result_reason VARCHAR(50),
    pgn TEXT,
    current_fen VARCHAR(255) NOT NULL DEFAULT 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
    bracket_round VARCHAR(100),
    bracket_position INT,
    total_cost_usd NUMERIC(10,6) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_games_tournament ON games(tournament_id);

CREATE TABLE moves (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    game_id UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    move_number INT NOT NULL,
    color VARCHAR(10) NOT NULL,
    san VARCHAR(20) NOT NULL,
    fen VARCHAR(255) NOT NULL,
    model_id VARCHAR(255),
    prompt_tokens INT,
    completion_tokens INT,
    cost_usd NUMERIC(10,8),
    response_time_ms BIGINT,
    retry_count INT NOT NULL DEFAULT 0,
    raw_response TEXT,
    is_override BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_moves_game ON moves(game_id, move_number);

CREATE TABLE chat_messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    game_id UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    move_number INT NOT NULL,
    sender_model VARCHAR(255) NOT NULL,
    sender_color VARCHAR(10) NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_messages_game ON chat_messages(game_id);
