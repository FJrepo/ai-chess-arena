package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.aichessarena.entity.TournamentParticipant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TournamentServiceTest {

    @Test
    void buildFirstRoundMatchesAssignsByesWithoutCreatingEmptyGames() {
        List<TournamentParticipant> participants = List.of(
                participant("Seed 1", 0),
                participant("Seed 2", 1),
                participant("Seed 3", 2),
                participant("Seed 4", 3),
                participant("Seed 5", 4)
        );

        List<TournamentService.FirstRoundMatch> matches = TournamentService.buildFirstRoundMatches(participants, 8);

        assertEquals(4, matches.size());
        assertEquals("Seed 1", matches.get(0).white().playerName);
        assertNull(matches.get(0).black());
        assertEquals("Seed 2", matches.get(1).white().playerName);
        assertNull(matches.get(1).black());
        assertEquals("Seed 3", matches.get(2).white().playerName);
        assertNull(matches.get(2).black());
        assertEquals("Seed 4", matches.get(3).white().playerName);
        assertEquals("Seed 5", matches.get(3).black().playerName);
    }

    @Test
    void getRoundNamesSupportsThirtyTwoPlayerBrackets() {
        assertArrayEquals(
                new String[]{"Round of 32", "Round of 16", "Quarterfinal", "Semifinal", "Final"},
                TournamentService.getRoundNames(32)
        );
        assertEquals("Round of 16", TournamentService.getNextRound("Round of 32"));
        assertEquals("Quarterfinal", TournamentService.getNextRound("Round of 16"));
        assertEquals("Semifinal", TournamentService.getNextRound("Quarterfinal"));
        assertEquals("Final", TournamentService.getNextRound("Semifinal"));
        assertNull(TournamentService.getNextRound("Final"));
    }

    private TournamentParticipant participant(String playerName, int seed) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.playerName = playerName;
        participant.seed = seed;
        return participant;
    }
}
