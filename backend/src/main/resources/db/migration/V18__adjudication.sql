-- Optional decisive-position adjudication, model-vs-model only. Existing tournaments keep playing to the end.
ALTER TABLE tournaments
    ADD COLUMN adjudication_enabled BOOLEAN NOT NULL DEFAULT FALSE;
