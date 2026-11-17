package dev.aichessarena.service;

import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.dto.MoveDto;
import dev.aichessarena.dto.TournamentExportDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.MoveAttemptRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read-only portable exports, independent of analytics aggregation and provider configuration. */
@ApplicationScoped
public class ExportService {
    @Inject
    MoveAttemptRepository attempts;

    private static final Comparator<Game> GAME_ORDER = Comparator
            .comparing((Game g) -> g.createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(g -> g.bracketRound, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(g -> g.bracketPosition, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(g -> g.seriesGameNumber)
            .thenComparing(g -> g.id, Comparator.nullsLast(Comparator.naturalOrder()));

    public String tournamentPgn(Tournament tournament, boolean includeChat) {
        return games(tournament).stream().filter(g -> !isBye(g))
                .map(g -> gamePgn(g, includeChat)).collect(Collectors.joining("\n"));
    }

    public String gamePgn(Game game, boolean includeChat) {
        StringBuilder out = new StringBuilder();
        tag(out, "Event", game.tournament == null ? "AI Chess Arena" : game.tournament.name);
        tag(out, "Site", "AI Chess Arena");
        var date = game.startedAt != null ? game.startedAt : game.createdAt;
        tag(out, "Date", date == null ? "????.??.??" : date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")));
        tag(out, "Round", round(game));
        tag(out, "White", game.whitePlayerName);
        tag(out, "Black", game.blackPlayerName);
        tag(out, "Result", result(game));
        if (game.whiteModelId != null) tag(out, "WhiteModel", game.whiteModelId);
        if (game.blackModelId != null) tag(out, "BlackModel", game.blackModelId);
        tag(out, "Termination", termination(game));
        if (game.resultReason != null) {
            tag(out, "TerminationDetails", game.resultReason.name().toLowerCase(Locale.ROOT));
        } else if (game.status == Game.GameStatus.CANCELLED) {
            tag(out, "TerminationDetails", "cancelled");
        } else if (game.status == Game.GameStatus.FORFEIT) {
            tag(out, "TerminationDetails", "forfeit");
        }
        if (game.openingName != null) tag(out, "Opening", game.openingName);
        if (game.openingEco != null) tag(out, "ECO", game.openingEco);
        Integer seconds = game.tournament == null ? null : game.tournament.moveTimeoutSeconds;
        tag(out, "TimeControl", seconds == null ? "-" : "*" + seconds);
        out.append('\n');
        List<Move> plies = CanonicalPlies.of(game.moves);
        for (Move move : plies) {
            out.append(move.moveNumber).append("WHITE".equals(move.color) ? ". " : "... ")
                    .append(move.san);
            if (move.evaluationMate != null) {
                String mate = move.evaluationMate == 0 && "BLACK".equals(move.color)
                        ? "-0" : move.evaluationMate.toString();
                out.append(" {[%eval #").append(mate).append("]}");
            } else if (move.evaluationCp != null) {
                out.append(" {[%eval ").append(BigDecimal.valueOf(move.evaluationCp, 2).toPlainString())
                        .append("]}");
            }
            if (move.isBook) out.append(" {book}");
            if (includeChat) {
                game.chatMessages.stream()
                        .filter(c -> c.moveNumber == move.moveNumber && Objects.equals(c.senderColor, move.color))
                        .forEach(c -> out.append(" {Chat: ").append(comment(c.message)).append('}'));
            }
            out.append('\n');
        }
        if (includeChat) {
            // Resignations have last words but no move; retain those and other unmatched messages too.
            game.chatMessages.stream().filter(c -> plies.stream()
                            .noneMatch(m -> m.moveNumber == c.moveNumber && Objects.equals(m.color, c.senderColor)))
                    .forEach(c -> out.append("{Chat ").append(c.moveNumber).append(' ')
                            .append(comment(c.senderColor)).append(": ").append(comment(c.message)).append("}\n"));
        }
        out.append(result(game)).append('\n');
        return out.toString();
    }

    public String tournamentCsv(Tournament tournament) {
        StringBuilder out = new StringBuilder();
        row(out, "game_id", "round", "bracket_position", "series_game", "white_model", "white_name",
                "black_model", "black_name", "result", "termination", "termination_details", "plies",
                "cost_usd", "white_acpl", "black_acpl", "white_blunders", "black_blunders", "opening", "eco");
        for (Game game : games(tournament)) {
            List<Move> moves = CanonicalPlies.of(game.moves);
            var summary = MoveQualityClassifier.summarize(moves, MoveQualityClassifier.analyze(moves));
            row(out, game.id, game.bracketRound, game.bracketPosition, game.seriesGameNumber,
                    game.whiteModelId, game.whitePlayerName, game.blackModelId, game.blackPlayerName,
                    result(game), termination(game), game.resultReason, moves.size(), game.totalCostUsd,
                    summary.white().averageCentipawnLoss(), summary.black().averageCentipawnLoss(),
                    summary.white().blunders(), summary.black().blunders(), game.openingName, game.openingEco);
        }
        return out.toString();
    }

    public TournamentExportDto tournamentJson(Tournament t) {
        var settings = new TournamentExportDto.Settings(t.format.name(), t.drawPolicy.name(),
                t.moveTimeoutSeconds, t.maxRetries, t.matchupBestOf, t.finalsBestOf, t.chatMode.name(),
                t.resignationAllowed, t.adjudicationEnabled, t.maxOutputTokens, effort(t.reasoningEffort),
                t.openingMode.name(), t.openingId, t.autoRunStatus.name(), t.maxConcurrentGames, t.budgetUsd);
        var participants = t.participants.stream()
                .sorted(Comparator.comparingInt((dev.aichessarena.entity.TournamentParticipant p) -> p.seed)
                        .thenComparing(p -> p.id, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(p -> new TournamentExportDto.Participant(p.id, p.playerName, p.modelId,
                        p.controlType.name(), p.seed, effort(p.reasoningEffort))).toList();
        List<TournamentExportDto.ExportGame> exportedGames = new ArrayList<>();
        for (Game game : games(t)) {
            List<Move> plies = CanonicalPlies.of(game.moves);
            var qualities = MoveQualityClassifier.analyze(plies);
            List<MoveDto> moves = new ArrayList<>();
            for (int i = 0; i < plies.size(); i++) {
                moves.add(DtoMapper.toDto(plies.get(i), qualities.get(i)));
            }
            var attemptSummaries = attempts.findByGameId(game.id).stream()
                    .map(a -> new TournamentExportDto.Attempt(a.id, a.moveNumber, a.color, a.attemptNumber,
                            a.modelId, a.outcome.name(), a.promptTokens, a.cachedPromptTokens, a.cacheWriteTokens,
                            a.completionTokens, a.reasoningTokens, a.effectiveMaxOutputTokens,
                            a.modelMaxCompletionTokens, effort(a.reasoningEffort), a.responseTimeMs,
                            a.costUsd, a.createdAt)).toList();
            exportedGames.add(new TournamentExportDto.ExportGame(game.id, game.bracketRound, game.bracketPosition,
                    game.seriesId, game.seriesGameNumber, game.seriesBestOf,
                    game.whiteParticipant == null ? null : game.whiteParticipant.id,
                    game.whitePlayerName, game.whiteModelId,
                    game.blackParticipant == null ? null : game.blackParticipant.id,
                    game.blackPlayerName, game.blackModelId,
                    game.advancedParticipant == null ? null : game.advancedParticipant.id,
                    game.status.name(), result(game), game.resultReason == null ? null : game.resultReason.name(),
                    termination(game), game.currentFen, game.openingId, game.openingName, game.openingEco,
                    game.createdAt, game.startedAt, game.completedAt, moves,
                    DtoMapper.toDto(MoveQualityClassifier.summarize(plies, qualities)),
                    game.chatMessages.stream().map(DtoMapper::toDto).toList(), attemptSummaries, game.totalCostUsd));
        }
        BigDecimal total = t.games.stream().map(g -> g.totalCostUsd).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TournamentExportDto(1, t.id, t.name, t.status.name(), t.createdAt, t.updatedAt,
                settings, participants, exportedGames, total);
    }

    /** UUID-only names avoid header injection, path traversal and dependence on user/model text. */
    public static String filename(String kind, UUID id, String extension) {
        return kind + "-" + id + "." + extension;
    }

    public static String result(Game game) {
        if (game.status == Game.GameStatus.CANCELLED || game.result == null
                || (game.status != Game.GameStatus.COMPLETED && game.status != Game.GameStatus.FORFEIT)) return "*";
        return switch (game.result) {
            case WHITE_WINS, BLACK_FORFEIT -> "1-0";
            case BLACK_WINS, WHITE_FORFEIT -> "0-1";
            case DRAW -> "1/2-1/2";
        };
    }

    /** PGN standard termination categories; detailed arena reasons are a separate tag/field. */
    public static String termination(Game game) {
        if (game.status == Game.GameStatus.CANCELLED) return "abandoned";
        if ("*".equals(result(game))) return "unterminated";
        if (game.resultReason == Game.ResultReason.TIMEOUT) return "time forfeit";
        if (game.resultReason == Game.ResultReason.ADJUDICATION) return "adjudication";
        if (game.status == Game.GameStatus.FORFEIT || game.result == Game.GameResult.WHITE_FORFEIT
                || game.result == Game.GameResult.BLACK_FORFEIT) return "rules infraction";
        return "normal";
    }

    private static String round(Game game) {
        return (game.bracketRound == null ? "?" : game.bracketRound) + "." + game.seriesGameNumber;
    }

    private static List<Game> games(Tournament tournament) {
        return tournament.games.stream().sorted(GAME_ORDER).toList();
    }

    private static boolean isBye(Game game) {
        return ("BYE".equals(game.whitePlayerName) && game.whiteParticipant == null)
                || ("BYE".equals(game.blackPlayerName) && game.blackParticipant == null);
    }

    private static String effort(ReasoningEffort effort) {
        return effort == null ? null : effort.apiValue();
    }

    private static void tag(StringBuilder out, String name, String value) {
        String safe = value == null || value.isBlank() ? "?" : value.replaceAll("[\\p{Cntrl}]", " ");
        out.append('[').append(name).append(" \"")
                .append(safe.replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\"]\n");
    }

    private static String comment(String text) {
        return text == null ? "" : text.replace('{', '(').replace('}', ')').replaceAll("[\\p{Cntrl}]", " ");
    }

    private static void row(StringBuilder out, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(',');
            out.append('"').append(csvCell(cells[i])).append('"');
        }
        out.append("\r\n");
    }

    /** RFC 4180 quoting plus spreadsheet formula protection, including whitespace-prefixed formulas. */
    static String csvCell(Object value) {
        if (value == null) return "";
        String text = value.toString();
        if (value instanceof String) {
            String start = text.replaceFirst("^[\\s\\p{Z}\\p{Cntrl}]+", "");
            if (!start.isEmpty() && "=+-@".indexOf(start.charAt(0)) >= 0) text = "'" + text;
        }
        return text.replace("\"", "\"\"");
    }
}
