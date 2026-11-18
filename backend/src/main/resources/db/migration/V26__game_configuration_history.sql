ALTER TABLE games ADD COLUMN starting_configuration JSONB;

CREATE TABLE game_configuration_revisions (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL CHECK (revision > 0),
    settings JSONB NOT NULL,
    UNIQUE (game_id, revision)
);

ALTER TABLE move_attempts ADD COLUMN configuration_revision INTEGER,
    ADD CONSTRAINT move_attempts_configuration_fk
        FOREIGN KEY (game_id, configuration_revision)
        REFERENCES game_configuration_revisions(game_id, revision)
        DEFERRABLE INITIALLY DEFERRED;
