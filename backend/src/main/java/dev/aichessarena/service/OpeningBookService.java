package dev.aichessarena.service;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.OpeningMode;
import dev.aichessarena.entity.Tournament;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/** Small authored book. Parsing validates every SAN against chesslib, also outside the test suite. */
@ApplicationScoped
public class OpeningBookService {
    public record Opening(String id, String eco, String name, List<String> moves) {}

    private static final List<Opening> BOOK = load();
    private final Random random;

    public OpeningBookService() {
        this(new SecureRandom());
    }

    OpeningBookService(Random random) {
        this.random = Objects.requireNonNull(random);
    }

    public List<Opening> list() {
        return BOOK;
    }

    public Opening require(String id) {
        return BOOK.stream().filter(line -> line.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown openingId: " + id));
    }

    /** Validate the effective settings before mutating the tournament. Null/omitted values retain settings. */
    public void configure(Tournament tournament, String modeValue, String idValue) {
        OpeningMode mode = tournament.openingMode;
        if (modeValue != null) {
            try {
                mode = OpeningMode.valueOf(modeValue.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("openingMode must be STANDARD, RANDOM_BOOK or FIXED");
            }
        }
        String id = idValue != null ? idValue.trim() : tournament.openingId;
        if (mode == OpeningMode.FIXED) {
            require(id);
        } else {
            if (idValue != null) {
                throw new IllegalArgumentException("openingId is only allowed with FIXED openingMode");
            }
            id = null;
        }
        tournament.openingMode = mode;
        tournament.openingId = id;
    }

    /** Called once per newly created game (never at start/resume or after a settings update). */
    public void prepare(Game game) {
        Tournament tournament = game.tournament;
        if (tournament == null || tournament.openingMode == OpeningMode.STANDARD) {
            return;
        }
        Opening line = tournament.openingMode == OpeningMode.FIXED
                ? require(tournament.openingId) : BOOK.get(random.nextInt(BOOK.size()));
        game.openingId = line.id();
        game.openingName = line.name();
        game.openingEco = line.eco();
        apply(game, line.moves());
    }

    /** Copy the stored snapshot, including a STANDARD partner, independently of the current tournament setting. */
    public void reuse(Game game, Game partner) {
        game.openingId = partner.openingId;
        game.openingName = partner.openingName;
        game.openingEco = partner.openingEco;
        apply(game, CanonicalPlies.of(partner.moves).stream().filter(move -> move.isBook).map(move -> move.san).toList());
    }

    private static void apply(Game game, List<String> sans) {
        if (!game.moves.isEmpty()) {
            throw new IllegalStateException("Opening can only be applied to a new game");
        }
        ChessService chess = new ChessService();
        Board board = chess.createBoard();
        StringBuilder pgn = new StringBuilder();
        for (String san : sans) {
            Move move = new Move();
            move.game = game;
            move.isBook = true;
            move.moveNumber = chess.getMoveNumber(board);
            move.color = chess.getSideToMove(board);
            ChessService.ValidMoveResult result = chess.validateAndApply(board, san);
            if (!result.valid()) {
                throw new IllegalStateException("Illegal book move: " + san);
            }
            move.san = san;
            move.fen = result.fen();
            game.moves.add(move);
            if (!pgn.isEmpty()) {
                pgn.append(' ');
            }
            if ("WHITE".equals(move.color)) {
                pgn.append(move.moveNumber).append(". ");
            }
            pgn.append(san);
        }
        if (!sans.isEmpty()) {
            game.pgn = pgn.toString();
            game.currentFen = board.getFen();
        }
    }

    private static List<Opening> load() {
        try (var stream = Objects.requireNonNull(OpeningBookService.class.getResourceAsStream("/openings.tsv"));
             var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            List<Opening> lines = reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> {
                        String[] fields = line.split("\t");
                        if (fields.length != 4) {
                            throw new IllegalStateException("Invalid opening row");
                        }
                        return new Opening(fields[0], fields[1], fields[2], List.of(fields[3].split(" ")));
                    }).toList();
            var ids = new HashSet<String>();
            for (Opening line : lines) {
                if (!ids.add(line.id()) || !line.id().matches("[a-z0-9-]{1,64}")
                        || !line.eco().matches("[A-E][0-9]{2}") || line.name().isBlank() || line.name().length() > 120
                        || line.moves().size() < 4 || line.moves().size() > 8) {
                    throw new IllegalStateException("Invalid opening: " + line.id());
                }
                Game game = new Game();
                apply(game, line.moves());
                if (new ChessService().isGameOver(new ChessService().boardFromFen(game.currentFen))) {
                    throw new IllegalStateException("Terminal opening: " + line.id());
                }
            }
            if (lines.size() < 30 || lines.size() > 50) {
                throw new IllegalStateException("Book must contain 30–50 openings");
            }
            return lines;
        } catch (Exception e) {
            throw new IllegalStateException("Could not load opening book", e);
        }
    }
}
