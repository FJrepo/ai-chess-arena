package dev.aichessarena.service;

import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.dto.MoveDto;
import dev.aichessarena.dto.TournamentExportDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.ExportRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read-only portable exports, independent of analytics aggregation and provider configuration. */
@ApplicationScoped
public class ExportService {
    /**
     * JSON export contract version: 2 since settings.maxRetries became settings.maxAttempts (2.0); 3 since every
     * timestamp carries an explicit UTC offset (2.0.1).
     */
    static final int SCHEMA_VERSION = 3;

    /** PGN Date: the UTC calendar date, whatever the server's time zone. */
    private static final DateTimeFormatter PGN_DATE =
            DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC);

    @Inject
    ExportRepository repository;

    public String tournamentPgn(UUID id, boolean includeChat) {
        return tournamentPgn(repository.read(id, false, includeChat, false).tournament(), includeChat);
    }

    public String gamePgn(UUID id, boolean includeChat) {
        return gamePgn(repository.read(id, true, includeChat, false).games().getFirst(), includeChat);
    }

    public String tournamentCsv(UUID id) {
        return tournamentCsv(repository.read(id, false, false, false).tournament());
    }

    public TournamentExportDto tournamentJson(UUID id) {
        var snapshot = repository.read(id, false, true, true);
        return tournamentJson(snapshot.tournament(), snapshot.attempts());
    }

    private static final Comparator<Game> GAME_ORDER = Comparator
            .comparing((Game g) -> g.createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(g -> g.bracketRound, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(g -> g.bracketPosition, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(g -> g.seriesGameNumber)
            .thenComparing(g -> g.id, Comparator.nullsLast(Comparator.naturalOrder()));

    public String tournamentPgn(Tournament tournament, boolean includeChat) {
        int openingSize = openingRoundSize(tournament);
        return games(tournament).stream().filter(g -> !isBye(g))
                .map(g -> gamePgn(g, includeChat, openingSize)).collect(Collectors.joining("\n"));
    }

    public String gamePgn(Game game, boolean includeChat) {
        return gamePgn(game, includeChat, game.tournament == null ? 2 : openingRoundSize(game.tournament));
    }

    private String gamePgn(Game game, boolean includeChat, int openingSize) {
        StringBuilder out = new StringBuilder();
        tag(out, "Event", game.tournament == null ? "AI Chess Arena" : game.tournament.name);
        tag(out, "Site", "AI Chess Arena");
        var date = game.startedAt != null ? game.startedAt : game.createdAt;
        tag(out, "Date", date == null ? "????.??.??" : PGN_DATE.format(date));
        tag(out, "Round", round(game, openingSize));
        tag(out, "White", game.whitePlayerName);
        tag(out, "Black", game.blackPlayerName);
        tag(out, "Result", result(game));
        if (game.bracketRound != null) tag(out, "BracketRound", game.bracketRound);
        tag(out, "SeriesGame", Integer.toString(game.seriesGameNumber));
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
        tag(out, "TimeControl", seconds == null ? "-" : "1/" + seconds);
        out.append('\n');
        Movetext text = new Movetext(out);
        List<Move> plies = CanonicalPlies.of(game.moves);
        for (Move move : plies) {
            text.token(move.moveNumber + ("WHITE".equals(move.color) ? "." : "..."));
            text.token(move.san);
            if (move.evaluationMate != null) {
                String mate = move.evaluationMate == 0 && "BLACK".equals(move.color)
                        ? "-0" : move.evaluationMate.toString();
                text.annotation("[%eval #" + mate + "]");
            } else if (move.evaluationCp != null) {
                text.annotation("[%eval " + BigDecimal.valueOf(move.evaluationCp, 2).toPlainString() + "]");
            }
            if (move.isBook) text.annotation("book");
            if (includeChat) {
                game.chatMessages.stream()
                        .filter(c -> c.moveNumber == move.moveNumber && Objects.equals(c.senderColor, move.color))
                        .forEach(c -> text.comment("Chat: " + comment(c.message)));
            }
            text.newline();
        }
        if (includeChat) {
            // Resignations have last words but no move; retain those and other unmatched messages too.
            game.chatMessages.stream().filter(c -> plies.stream()
                            .noneMatch(m -> m.moveNumber == c.moveNumber && Objects.equals(m.color, c.senderColor)))
                    .forEach(c -> {
                        text.comment("Chat " + c.moveNumber + " " + comment(c.senderColor) + ": " + comment(c.message));
                        text.newline();
                    });
        }
        text.token(result(game));
        text.newline();
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

    private TournamentExportDto tournamentJson(Tournament t, Map<UUID, List<TournamentExportDto.Attempt>> attempts) {
        var settings = new TournamentExportDto.Settings(t.format.name(), t.drawPolicy.name(),
                t.moveTimeoutSeconds, t.maxAttempts, t.matchupBestOf, t.finalsBestOf, t.chatMode.name(),
                t.banterLevel != null ? t.banterLevel.name() : null,
                t.resignationAllowed, t.legalMoveHints, t.adjudicationEnabled, t.maxOutputTokens, effort(t.reasoningEffort),
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
            var attemptSummaries = attempts.getOrDefault(game.id, List.of());
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
        return new TournamentExportDto(SCHEMA_VERSION, t.id, t.name, t.status.name(), t.createdAt, t.updatedAt,
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

    private static String round(Game game, int openingSize) {
        int size = roundSize(game.bracketRound);
        if (size < 2) return "?";
        int number = 1;
        while (openingSize > size) {
            openingSize /= 2;
            number++;
        }
        // chesslib accepts only an integer Round; SeriesGame preserves the series component separately.
        return Integer.toString(number);
    }

    private static int openingRoundSize(Tournament tournament) {
        int size = 2;
        while (size < tournament.participants.size()) size *= 2;
        for (Game game : tournament.games) size = Math.max(size, roundSize(game.bracketRound));
        return size;
    }

    private static int roundSize(String label) {
        try {
            int size = TournamentService.roundSizeForName(label);
            return size >= 2 && (size & (size - 1)) == 0 ? size : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
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
        return text == null ? "" : text.replace('{', '(').replace('}', ')')
                .replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ").replace("[%", "[ %");
    }

    /** Wrap only movetext/comment content. Tag values and SAN/annotation tokens stay intact. */
    private static final class Movetext {
        private static final int WIDTH = 79;
        private final StringBuilder out;
        private int column;

        Movetext(StringBuilder out) {
            this.out = out;
        }

        void token(String token) {
            token(token, false);
        }

        private void token(String token, boolean insideComment) {
            int width = token.codePointCount(0, token.length());
            boolean separator = column > 0 && out.charAt(out.length() - 1) != ' ';
            if (column + (separator ? 1 : 0) + width > WIDTH) {
                if (insideComment) continuation();
                else newline();
                separator = false;
            }
            if (separator) {
                out.append(' ');
                column++;
            }
            out.append(token);
            column += width;
        }

        void annotation(String annotation) {
            token("{" + annotation + "}");
        }

        void comment(String comment) {
            for (String word : ("{" + comment + "}").split("\\s+")) {
                // Chat can contain an unbroken word longer than a line. Splits remain inside braces.
                while (word.codePointCount(0, word.length()) > WIDTH - 1) {
                    int end = word.offsetByCodePoints(0, WIDTH - 1);
                    token(word.substring(0, end), true);
                    continuation();
                    word = word.substring(end);
                }
                token(word, true);
            }
        }

        private void continuation() {
            newline();
            // PGN treats '%' in column one as an escape line, even inside a comment.
            out.append(' ');
            column = 1;
        }

        void newline() {
            if (column > 0) out.append('\n');
            column = 0;
        }
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
