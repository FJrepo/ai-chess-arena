package dev.aichessarena.repository;

import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ChatMessageRepository implements PanacheRepositoryBase<ChatMessage, UUID> {

    /** Returns the persisted message, or null for a silent move or resignation. */
    public ChatMessage persistMessage(Game game, int moveNumber, String color, String modelId, String message) {
        if (message == null || message.isBlank()) return null;
        ChatMessage chat = new ChatMessage();
        chat.game = game;
        chat.moveNumber = moveNumber;
        chat.senderColor = color;
        chat.senderModel = modelId;
        chat.message = message;
        persist(chat);
        return chat;
    }

    public List<ChatMessage> findByGameId(UUID gameId) {
        return list("game.id = ?1 order by createdAt", gameId);
    }

    /** The message attached to this exact ply, never an older message from the same player. */
    public ChatMessage findByPly(UUID gameId, int moveNumber, String color) {
        return find("game.id = ?1 and moveNumber = ?2 and senderColor = ?3 order by createdAt desc",
                gameId, moveNumber, color).firstResult();
    }

    /**
     * The ply and length (characters, without surrounding whitespace) of every chat message in the games of an
     * analytics window (see {@link GameRepository#findInWindow}), oldest first; the text itself is not loaded.
     */
    public List<PlyMessage> findPlyMessagesInWindow(Instant cutoff, UUID tournamentId) {
        TypedQuery<Object[]> query = getEntityManager().createQuery(
                "select c.game.id, c.moveNumber, c.senderColor, length(trim(c.message)) from ChatMessage c where "
                        + GameRepository.windowPredicate("c.game")
                        + GameRepository.tournamentPredicate("c.game", tournamentId)
                        + " order by c.createdAt, c.id",
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
