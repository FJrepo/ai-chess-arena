package dev.aichessarena.service;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/** The game changed between validation and write (status or position); maps to HTTP 409. */
public class GameStateConflictException extends WebApplicationException {

    public GameStateConflictException(String message) {
        super(message, Response.Status.CONFLICT);
    }
}
