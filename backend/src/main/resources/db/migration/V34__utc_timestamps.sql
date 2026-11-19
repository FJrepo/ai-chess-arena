-- Timestamps become absolute instants (timestamptz). Earlier versions stored the backend's wall-clock time without a
-- zone; Docker installs run the backend in UTC, so the stored values are read as UTC. A source install that ran the
-- backend in another JVM time zone stored that zone's wall-clock time instead: see the 2.0.1 upgrade notes.
--
-- timestamp -> timestamptz reads values in the session time zone. With the session in UTC, PostgreSQL (12+) only
-- changes the column type and rewrites no table, so the ACCESS EXCLUSIVE locks are held briefly. The one index keyed
-- on a converted column, the small partial index idx_practice_games_created, is rebuilt.
-- SET LOCAL ends with this migration's transaction.
SET LOCAL TIME ZONE 'UTC';

ALTER TABLE tournaments
    ALTER COLUMN created_at TYPE TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ;

ALTER TABLE games
    ALTER COLUMN created_at TYPE TIMESTAMPTZ,
    ALTER COLUMN started_at TYPE TIMESTAMPTZ,
    ALTER COLUMN completed_at TYPE TIMESTAMPTZ;

ALTER TABLE moves
    ALTER COLUMN created_at TYPE TIMESTAMPTZ;

ALTER TABLE chat_messages
    ALTER COLUMN created_at TYPE TIMESTAMPTZ;

ALTER TABLE move_attempts
    ALTER COLUMN created_at TYPE TIMESTAMPTZ;
