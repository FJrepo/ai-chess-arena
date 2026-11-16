package dev.aichessarena.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
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

    @Test
    void gameCarriesParticipantIdsSoEqualNamesStayDistinct() {
        TournamentParticipant white = participant("Same");
        TournamentParticipant black = participant("Same");
        Game game = game(null, "Final", black);
        game.whiteParticipant = white;
        game.whitePlayerName = white.playerName;
        game.blackParticipant = black;
        game.blackPlayerName = black.playerName;

        GameDto dto = DtoMapper.toDto(game, false);

        assertEquals(white.id, dto.whiteParticipantId());
        assertEquals(black.id, dto.blackParticipantId());
        assertEquals(black.id, dto.advancedParticipantId());
    }

    @Test
    void sideWithoutParticipantHasNoParticipantId() {
        Game bye = game(null, "Quarterfinal", null);
        bye.whiteParticipant = participant("BYE");
        bye.blackPlayerName = "BYE";

        GameDto dto = DtoMapper.toDto(bye, false);

        assertEquals(bye.whiteParticipant.id, dto.whiteParticipantId());
        assertNull(dto.blackParticipantId());
        assertNull(dto.advancedParticipantId());
    }

    @Test
    void gameWithMovesCarriesMoveQualityAndSummary() {
        Game game = game(null, "Final", null);
        game.moves.add(move(game, 1, "WHITE", 30));
        game.moves.add(move(game, 1, "BLACK", 400));
        game.moves.add(move(game, 2, "WHITE", null));

        GameDto dto = DtoMapper.toDto(game, true);

        MoveQualityDto first = dto.moves().get(0).quality();
        assertEquals(0, first.centipawnLoss());
        assertNull(first.judgement());
        assertEquals(0.5, first.winChanceBefore());
        MoveQualityDto second = dto.moves().get(1).quality();
        assertEquals("BLUNDER", second.judgement());
        assertEquals(370, second.centipawnLoss());
        assertNull(second.excluded());
        assertNull(dto.moves().get(2).quality(), "not evaluated yet");
        assertEquals(1, dto.qualitySummary().white().analyzedMoves());
        assertEquals(0, dto.qualitySummary().white().averageCentipawnLoss());
        assertEquals(1, dto.qualitySummary().black().blunders());
        assertEquals(370, dto.qualitySummary().black().averageCentipawnLoss());
    }

    @Test
    void gameWithoutMovesHasNoQualitySummary() {
        Game game = game(null, "Final", null);
        game.moves.add(move(game, 1, "WHITE", 30));

        GameDto dto = DtoMapper.toDto(game, false);

        assertTrue(dto.moves().isEmpty());
        assertNull(dto.qualitySummary());
    }

    @Test
    void overrideMoveIsMarkedAsExcluded() {
        Game game = game(null, "Final", null);
        game.moves.add(move(game, 1, "WHITE", 30));
        Move override = move(game, 1, "BLACK", 30);
        override.isOverride = true;
        game.moves.add(override);

        MoveQualityDto quality = DtoMapper.toDto(game, true).moves().get(1).quality();

        assertEquals("OVERRIDE", quality.excluded());
        assertNull(quality.judgement());
        assertNull(quality.centipawnLoss());
    }

    private static Move move(Game game, int number, String color, Integer cp) {
        Move move = new Move();
        move.id = UUID.randomUUID();
        move.game = game;
        move.moveNumber = number;
        move.color = color;
        move.san = "x";
        move.fen = "fen";
        move.evaluationCp = cp;
        return move;
    }

    private static TournamentParticipant participant(String name) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.id = UUID.randomUUID();
        participant.playerName = name;
        participant.controlType = TournamentParticipant.ControlType.AI;
        return participant;
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
