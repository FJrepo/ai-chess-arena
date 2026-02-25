package dev.aichessarena.repository;

import dev.aichessarena.entity.ChatMessage;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ChatMessageRepository implements PanacheRepositoryBase<ChatMessage, UUID> {

    public List<ChatMessage> findByGameId(UUID gameId) {
        return list("game.id = ?1 order by createdAt", gameId);
    }
}
