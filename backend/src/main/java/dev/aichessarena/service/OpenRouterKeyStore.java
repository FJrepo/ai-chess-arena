package dev.aichessarena.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** The only runtime credential source. No credential-bearing object has a generated toString. */
@ApplicationScoped
public class OpenRouterKeyStore {
    private final Function<String, String> environment;
    private final String configured;
    private final Path directory;
    private String validatedFingerprint;
    private Instant lastValidatedAt;
    private String validation = "unknown";

    @Inject
    public OpenRouterKeyStore(@ConfigProperty(name = "openrouter.api-key") Optional<String> configured,
                              @ConfigProperty(name = "instance.secrets-dir") String directory) {
        this(configured.orElse(""), Path.of(directory), System::getenv);
    }

    OpenRouterKeyStore(String configured, Path directory, Function<String, String> environment) {
        this.configured = configured;
        this.directory = directory;
        this.environment = environment;
    }

    Path directory() {
        return directory;
    }

    public String key() {
        String explicit = environment.apply("OPENROUTER_API_KEY");
        if (explicit != null && !explicit.isBlank()) return explicit;
        String file = environment.apply("OPENROUTER_API_KEY_FILE");
        if (file != null && !file.isBlank()) {
            String value = PrivateFiles.read(Path.of(file));
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("OPENROUTER_API_KEY_FILE must name a readable, non-empty secret file");
            }
            return value;
        }
        if (configured != null && !configured.isBlank()) return configured;
        String value = PrivateFiles.read(directory.resolve("openrouter_key"));
        return value == null ? "" : value;
    }

    public String source() {
        String explicit = environment.apply("OPENROUTER_API_KEY");
        if (explicit != null && !explicit.isBlank()) return "env";
        String file = environment.apply("OPENROUTER_API_KEY_FILE");
        if (file != null && !file.isBlank()) return "file";
        if (configured != null && !configured.isBlank()) return "env";
        return key().isBlank() ? "none" : "ui";
    }

    public void requireKey() {
        if (key().isBlank()) throw new WebApplicationException("Connect OpenRouter to create or run games", 409);
    }

    public void requireMutable() {
        if (source().equals("env") || source().equals("file")) {
            throw new WebApplicationException("OpenRouter key is operator-managed; change its environment or secret file", 409);
        }
    }

    synchronized void save(String key) {
        requireMutable();
        PrivateFiles.write(directory.resolve("openrouter_key"), key);
        checked(key, "valid");
    }

    public synchronized void delete() {
        requireMutable();
        PrivateFiles.delete(directory.resolve("openrouter_key"));
        validatedFingerprint = null;
        lastValidatedAt = null;
        validation = "unknown";
    }

    synchronized void checked(String key, String result) {
        validatedFingerprint = Pkce.challenge(key);
        lastValidatedAt = Instant.now();
        validation = result;
    }

    public synchronized Status status() {
        String key = key();
        boolean same = Pkce.challenge(key).equals(validatedFingerprint);
        // Never reveal the whole value, even for an invalid short operator credential.
        String suffix = key.length() > 4 ? "••••" + key.substring(key.length() - 4) : null;
        return new Status(source(), suffix, same ? lastValidatedAt : null,
                key.isBlank() ? "none" : same ? validation : "unknown");
    }

    public record Status(String source, String maskedSuffix, Instant lastValidatedAt, String validation) {}
}
