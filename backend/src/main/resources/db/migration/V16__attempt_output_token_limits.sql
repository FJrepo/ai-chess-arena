-- Historical attempts have no reliable request-cap metadata; leave both values null.
ALTER TABLE move_attempts ADD COLUMN effective_max_output_tokens INTEGER;
ALTER TABLE move_attempts ADD COLUMN model_max_completion_tokens INTEGER;
