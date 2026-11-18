-- 2.0 API cleanup.
-- 1. The attempts limit is called maxAttempts everywhere (it always counted the first attempt). Values and the
--    1-20 range are unchanged.
ALTER TABLE tournaments RENAME COLUMN max_retries TO max_attempts;

-- 2. Stored configuration snapshots use the new JSON key, so revisions recorded before and after the upgrade compare
--    equal when nothing else changed.
UPDATE games
SET starting_configuration = (starting_configuration - 'maxRetries')
        || jsonb_build_object('maxAttempts', starting_configuration -> 'maxRetries')
WHERE starting_configuration ? 'maxRetries';

UPDATE game_configuration_revisions
SET settings = (settings - 'maxRetries') || jsonb_build_object('maxAttempts', settings -> 'maxRetries')
WHERE settings ? 'maxRetries';

-- 3. The pre-1.4 chat switch. chat_mode (V12) has been the only source since 1.4; this column was only kept in sync.
ALTER TABLE tournaments DROP COLUMN trash_talk_enabled;
