package dev.aichessarena.repository;

import dev.aichessarena.entity.ChatMessage;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.TypedQuery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ChatMessageRepository implements PanacheRepositoryBase<ChatMessage, UUID> {

    public List<ChatMessage> findByGameId(UUID gameId) {
        return list("game.id = ?1 order by createdAt", gameId);
    }

    /**
     * The ply and length (characters, without surrounding whitespace) of every chat message in the games of an
     * analytics window (see {@link GameRepository#findInWindow}); the text itself is not loaded.
     */
    public List<PlyMessage> findPlyMessagesInWindow(LocalDateTime cutoff, UUID tournamentId) {
        TypedQuery<Object[]> query = getEntityManager().createQuery(
                "select c.game.id, c.moveNumber, c.senderColor, length(trim(c.message)) from ChatMessage c where "
                        + GameRepository.windowPredicate("c.game")
                        + GameRepository.tournamentPredicate("c.game", tournamentId),
                Object[].class
        );
        Parameters parameters = GameRepository.windowParameters(cutoff, tournamentId);
        for (Map.Entry<String, Object> parameter : parameters.map().entrySet()) {
            query.setParameter(parameter.getKey(), parameter.getValue());
        }
        return query.getResultList().stream()
                .map(row -> new PlyMessage(
                        (UUID) row[0], ((Number) row[1]).intValue(), (String) row[2], ((Number) row[3]).intValue()))
                .toList();
    }

    /** A chat message reduced to its ply (game, move number, sender colour) and its length in characters. */
    public record PlyMessage(UUID gameId, int moveNumber, String color, int length) {
    }
}
