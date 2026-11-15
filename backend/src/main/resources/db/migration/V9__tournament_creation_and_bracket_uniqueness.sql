-- Client-generated id of the create request, so a retried creation returns the same tournament.
ALTER TABLE tournaments
    ADD COLUMN IF NOT EXISTS client_request_id UUID;

CREATE UNIQUE INDEX IF NOT EXISTS uq_tournaments_client_request_id
    ON tournaments (client_request_id)
    WHERE client_request_id IS NOT NULL;

-- One game per bracket slot and series game. Databases that already contain duplicate brackets
-- (generated twice by older versions) keep migrating; the application-level lock still applies.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM games
        WHERE tournament_id IS NOT NULL
        GROUP BY tournament_id, bracket_round, bracket_position, series_game_number
        HAVING COUNT(*) > 1
    ) THEN
        RAISE NOTICE 'Skipping uq_games_bracket_slot: duplicate bracket games exist';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS uq_games_bracket_slot
            ON games (tournament_id, bracket_round, bracket_position, series_game_number)
            WHERE tournament_id IS NOT NULL;
    END IF;
END
$$;

-- The game that decided its series records who advanced (needed when a draw policy decided a level series).
ALTER TABLE games
    ADD COLUMN IF NOT EXISTS advanced_participant_id UUID REFERENCES tournament_participants(id) ON DELETE SET NULL;
