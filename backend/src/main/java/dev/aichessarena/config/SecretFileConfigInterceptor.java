package dev.aichessarena.config;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Expressions;
import io.smallrye.config.Priorities;
import jakarta.annotation.Priority;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Function;

/** Resolves runtime secret files without exporting their contents to the process environment. */
@Priority(Priorities.LIBRARY + 350)
public final class SecretFileConfigInterceptor implements ConfigSourceInterceptor {
    private static final long serialVersionUID = 1L;
    private final Function<String, String> environment;

    public SecretFileConfigInterceptor() {
        this(System::getenv);
    }

    SecretFileConfigInterceptor(Function<String, String> environment) {
        this.environment = environment;
    }

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        String environmentName = switch (name) {
            case "openrouter.api-key" -> "OPENROUTER_API_KEY";
            case "quarkus.datasource.password" -> "DB_PASSWORD";
            default -> null;
        };
        if (environmentName == null) {
            return context.proceed(name);
        }
        ConfigValue configured = Expressions.withoutExpansion(() -> context.proceed(name));
        if (configured != null && configured.getConfigSourceOrdinal() > 300) {
            return context.proceed(name);
        }
        // Preserve direct Quarkus overrides, while handling EnvConfigSource's automatic
        // OPENROUTER_API_KEY -> openrouter.api-key alias (including Compose's empty value).
        if (configured != null && configured.getValue() != null
                && !configured.getValue().isEmpty()
                && !isApplicationPlaceholder(environmentName, configured.getValue())
                && !(configured.getConfigSourceOrdinal() == 300
                && configured.getValue().equals(environment.apply(environmentName)))) {
            return context.proceed(name);
        }
        String secret = resolve(environmentName);
        if (secret == null) {
            return context.proceed(name);
        }
        // Resolve after expression expansion, so '$', '${...}' and backslashes stay literal.
        return ConfigValue.builder().withName(name).withValue(secret)
                .withConfigSourceName("Secret files").withConfigSourceOrdinal(310).build();
    }

    private static boolean isApplicationPlaceholder(String environmentName, String value) {
        return switch (environmentName) {
            case "DB_PASSWORD" -> value.equals("${DB_PASSWORD}") || value.equals("${DB_PASSWORD:chess}");
            case "OPENROUTER_API_KEY" -> value.equals("${OPENROUTER_API_KEY}") || value.equals("${OPENROUTER_API_KEY:}");
            default -> false;
        };
    }

    String resolve(String propertyName) {
        String explicit = environment.apply(propertyName);
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        String file = environment.apply(propertyName + "_FILE");
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            String value = Files.readString(Path.of(file)).trim();
            if (!value.isEmpty()) {
                return value;
            }
        } catch (IOException | InvalidPathException | SecurityException ignored) {
            // Do not propagate a cause: file paths and filesystem errors may contain secrets.
        }
        throw new IllegalStateException(propertyName + "_FILE must name a readable, non-empty secret file");
    }
}
