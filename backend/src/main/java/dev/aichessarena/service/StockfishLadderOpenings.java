package dev.aichessarena.service;

import dev.aichessarena.entity.BenchmarkDefinition.Opening;
import dev.aichessarena.entity.Game;
import java.util.List;

/** Authored standard-chess suite. Existing runs keep their own copy of every SAN line. */
final class StockfishLadderOpenings {
    static final String SUITE = "stockfish-ladder-v1";

    static final List<Opening> OPENINGS = List.of(
            line("ruy-lopez-closed", "Closed Ruy Lopez",
                    "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Be7 Re1 b5 Bb3 d6 c3 O-O h3 Nb8 d4 Nbd7 "
                            + "Nbd2 Bb7 Bc2 Re8 Nf1 Bf8 Ng3 g6 a4 c5"),
            line("sicilian-najdorf", "Sicilian Najdorf",
                    "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6 Be3 e5 Nb3 Be6 f3 h5 Qd2 Nbd7 O-O-O b5 "
                            + "Kb1 Be7 h3 O-O a3 Rc8 Qf2 Rxc3 bxc3 Qc7 Bd3"),
            line("french-winawer", "French Winawer",
                    "e4 e6 d4 d5 Nc3 Bb4 e5 c5 a3 Bxc3+ bxc3 Ne7 Qg4 Qc7 Qxg7 Rg8 Qxh7 cxd4 "
                            + "Ne2 Nbc6 f4 Bd7 Qd3 dxc3 Nxc3 a6"),
            line("caro-kann-classical", "Classical Caro-Kann",
                    "e4 c6 d4 d5 Nc3 dxe4 Nxe4 Bf5 Ng3 Bg6 h4 h6 Nf3 Nd7"),
            line("queens-gambit-declined", "Queen's Gambit Declined",
                    "d4 d5 c4 e6 Nc3 Nf6 Bg5 Be7 e3 O-O Nf3 Nbd7 Rc1 c6 Bd3 dxc4 Bxc4 Nd5 "
                            + "Bxe7 Qxe7 O-O Nxc3 Rxc3 b6 Bd3 Bb7 Qc2 h6"),
            line("kings-indian-classical", "Classical King's Indian",
                    "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6 Nf3 O-O Be2 e5 O-O Nc6 d5 Ne7"),
            line("english-symmetrical", "Symmetrical English",
                    "c4 c5 Nc3 Nc6 g3 g6 Bg2 Bg7"),
            line("dutch-leningrad", "Leningrad Dutch",
                    "d4 f5 g3 Nf6 Bg2 g6 Nf3 Bg7 O-O O-O c4")
    );

    private StockfishLadderOpenings() {}

    private static Opening line(String id, String name, String sans) {
        List<String> moves = List.of(sans.split(" "));
        var board = OpeningBookService.apply(new Game(), moves);
        if (new ChessService().isGameOver(board)) {
            throw new IllegalStateException("Terminal ladder opening: " + id);
        }
        return new Opening(id, name, moves);
    }
}
