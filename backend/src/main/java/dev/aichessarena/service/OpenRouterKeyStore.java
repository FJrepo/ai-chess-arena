package dev.aichessarena.service;

import dev.aichessarena.config.SecretFileConfigInterceptor;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.nio.file.Path;
import java.time.Instant;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** The only runtime credential source. No credential-bearing object has a generated toString. */
@ApplicationScoped
public class OpenRouterKeyStore {
    private final SmallRyeConfig config;
    private final Path directory;
    private String validatedFingerprint;
    private Instant lastValidatedAt;
    private String validation = "unknown";

    @Inject
    public OpenRouterKeyStore(Config config,
                              @ConfigProperty(name = "instance.secrets-dir") String directory) {
        this.config = config.unwrap(SmallRyeConfig.class);
        this.directory = Path.of(directory);
    }

    Path directory() {
        return directory;
    }

    public String key() {
        return key(config.getConfigValue("openrouter.api-key"));
    }

    private String key(ConfigValue configured) {
        if (configured.getValue() != null && !configured.getValue().isBlank()) return configured.getValue();
        String value = PrivateFiles.read(directory.resolve("openrouter_key"));
        return value == null ? "" : value;
    }

    public String source() {
        ConfigValue configured = config.getConfigValue("openrouter.api-key");
        return source(configured, key(configured));
    }

    private String source(ConfigValue configured, String key) {
        if (configured.getValue() != null && !configured.getValue().isBlank()) {
            return SecretFileConfigInterceptor.FILE_SOURCE.equals(configured.getConfigSourceName()) ? "file" : "env";
        }
        return key.isBlank() ? "none" : "ui";
    }

    public void requireKey() {
        if (key().isBlank()) throw new WebApplicationException("Connect OpenRouter to create or run games", 409);
    }

    public void requireMutable() {
        String source = source();
        if (source.equals("env") || source.equals("file")) {
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
        ConfigValue configured = config.getConfigValue("openrouter.api-key");
        String key = key(configured);
        boolean same = Pkce.challenge(key).equals(validatedFingerprint);
        // Never reveal the whole value, even for an invalid short operator credential.
        String suffix = key.length() > 4 ? "••••" + key.substring(key.length() - 4) : null;
        return new Status(source(configured, key), suffix, same ? lastValidatedAt : null,
                key.isBlank() ? "none" : same ? validation : "unknown");
    }

    public record Status(String source, String maskedSuffix, Instant lastValidatedAt, String validation) {}
}
