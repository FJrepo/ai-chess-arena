package dev.aichessarena.repository;

import dev.aichessarena.dto.LiveGamesDto;
import dev.aichessarena.dto.LiveGamesDto.GameSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class LiveGamesRepository {
    @Inject
    EntityManager entityManager;

    public LiveGamesDto snapshot() {
        List<Object[]> rows = entityManager.createNativeQuery("""
                with selected as (
                    select id, case when status = 'IN_PROGRESS' then 0 else 1 end as bucket
                    from games where status in ('IN_PROGRESS', 'PAUSED')
                    union all
                    (select id, 2 as bucket from games
                     where status in ('COMPLETED', 'FORFEIT') and completed_at is not null
                       and (practice_kind is not null or
                           ((white_participant_id is not null or btrim(white_player_name) not in ('', 'BYE'))
                            and (black_participant_id is not null or btrim(black_player_name) not in ('', 'BYE'))))
                     order by completed_at desc, id
                     limit 1)
                )
                select g.id, g.status, t.name, g.white_player_name, g.black_player_name,
                       g.practice_kind is not null, g.current_fen,
                       latest.id, latest.evaluation_cp, latest.evaluation_mate
                from selected s
                join games g on g.id = s.id
                left join tournaments t on t.id = g.tournament_id
                left join lateral (
                    select id, evaluation_cp, evaluation_mate
                    from moves where game_id = g.id
                    order by move_number desc, color, created_at
                    fetch first 1 row with ties
                ) latest on true
                order by s.bucket, g.started_at desc nulls last, g.id
                """, Object[].class).getResultList();

        Map<UUID, ScoredGame> active = new LinkedHashMap<>();
        ScoredGame lastFinished = null;
        for (Object[] row : rows) {
            GameSummary summary = new GameSummary((UUID) row[0], (String) row[1], (String) row[2],
                    (String) row[3], (String) row[4], (boolean) row[5], fullMoveNumber((String) row[6]),
                    (Integer) row[8], (Integer) row[9]);
            var scored = new ScoredGame(summary, (UUID) row[7]);
            if ("IN_PROGRESS".equals(summary.status()) || "PAUSED".equals(summary.status())) {
                active.merge(summary.id(), scored, LiveGamesRepository::earliestId);
            } else {
                lastFinished = lastFinished == null ? scored : earliestId(lastFinished, scored);
            }
        }
        return new LiveGamesDto(active.values().stream().map(ScoredGame::summary).toList(),
                lastFinished == null ? null : lastFinished.summary());
    }

    private static Integer fullMoveNumber(String fen) {
        if (fen == null) return null;
        String[] fields = fen.trim().split("\\s+");
        if (fields.length != 6) return null;
        try {
            int number = Integer.parseInt(fields[5]);
            return number > 0 ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Tied rows share a ply and timestamp. Use the same UUID order as CanonicalPlies. */
    private static ScoredGame earliestId(ScoredGame first, ScoredGame second) {
        return first.moveId().compareTo(second.moveId()) <= 0 ? first : second;
    }

    private record ScoredGame(GameSummary summary, UUID moveId) {}
}
