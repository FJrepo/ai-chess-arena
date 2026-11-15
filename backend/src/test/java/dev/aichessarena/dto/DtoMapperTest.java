package dev.aichessarena.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DtoMapperTest {

    @Test
    void completedTournamentReportsTheChampionRecordedOnTheFinal() {
        Tournament tournament = new Tournament();
        tournament.id = UUID.randomUUID();
        tournament.name = "Cup";
        TournamentParticipant champion = new TournamentParticipant();
        champion.id = UUID.randomUUID();
        champion.playerName = "Seed 2";
        champion.controlType = TournamentParticipant.ControlType.AI;
        Game semifinal = game(tournament, "Semifinal", null);
        Game finalGame = game(tournament, "Final", champion);
        tournament.games.add(semifinal);
        tournament.games.add(finalGame);

        assertNull(DtoMapper.toDto(tournament).winnerName(), "not completed yet");

        tournament.status = Tournament.TournamentStatus.COMPLETED;
        TournamentDto dto = DtoMapper.toDto(tournament);

        assertEquals("Seed 2", dto.winnerName());
        assertEquals("Seed 2", dto.games().get(1).advancedPlayerName());
    }

    private static Game game(Tournament tournament, String round, TournamentParticipant advanced) {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.tournament = tournament;
        game.bracketRound = round;
        game.bracketPosition = 0;
        game.advancedParticipant = advanced;
        return game;
    }
}
