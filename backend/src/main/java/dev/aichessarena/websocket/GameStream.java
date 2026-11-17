package dev.aichessarena.websocket;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

/** Identifies the backend process that owns the current game revision timeline. */
@ApplicationScoped
public class GameStream {

    private final String id = UUID.randomUUID().toString();

    public String id() {
        return id;
    }
}
