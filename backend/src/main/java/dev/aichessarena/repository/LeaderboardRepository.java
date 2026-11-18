package dev.aichessarena.repository;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameResult;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class LeaderboardRepository implements PanacheRepositoryBase<Game, UUID> {

    /** Fetch both to-one sides in one query; never load moves or make a query per participant. */
    public List<Game> findFinishedModelGames(boolean includeForfeits, Boolean legalMoveHints) {
        String from = "select g from Game g left join fetch g.whiteParticipant left join fetch g.blackParticipant";
        return list(from + rankedGameWhere(includeForfeits, legalMoveHints),
                rankedGameParameters(includeForfeits, legalMoveHints));
    }

    static String rankedGameWhere(boolean includeForfeits, Boolean legalMoveHints) {
        return GameRepository.hintsJoin(legalMoveHints)
                + " where g.practiceKind is null and g.status in :statuses and g.completedAt is not null and g.result is not null"
                + " and g.whiteModelId is not null and g.blackModelId is not null"
                + (includeForfeits ? "" : " and g.result not in :forfeits")
                + GameRepository.hintsPredicate(legalMoveHints);
    }

    static Parameters rankedGameParameters(boolean includeForfeits, Boolean legalMoveHints) {
        var parameters = Parameters.with("statuses", List.of(Game.GameStatus.COMPLETED, Game.GameStatus.FORFEIT));
        if (!includeForfeits) {
            parameters.and("forfeits", List.of(GameResult.WHITE_FORFEIT, GameResult.BLACK_FORFEIT));
        }
        return GameRepository.hintsParameters(parameters, legalMoveHints);
    }
}
