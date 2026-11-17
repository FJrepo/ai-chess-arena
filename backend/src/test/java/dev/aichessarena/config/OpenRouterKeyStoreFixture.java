package dev.aichessarena.config;

import dev.aichessarena.service.OpenRouterKeyStore;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.nio.file.Path;
import java.util.Map;

/** Test-only configuration sources; no process environment or real credentials are consulted. */
public final class OpenRouterKeyStoreFixture {
    private OpenRouterKeyStoreFixture() {}

    public static OpenRouterKeyStore keys(String property, Path directory) {
        return keys(property, directory, Map.of());
    }

    public static OpenRouterKeyStore keys(String property, Path directory, Map<String, String> environment) {
        var builder = new SmallRyeConfigBuilder().addDefaultInterceptors()
                .withInterceptors(new SecretFileConfigInterceptor(environment::get))
                .withSources(new EnvConfigSource(environment, 300), new PropertiesConfigSource(
                        Map.of("openrouter.api-key", "${OPENROUTER_API_KEY:}"), "application", 250));
        if (property != null) {
            builder.withSources(new PropertiesConfigSource(Map.of("openrouter.api-key", property), "system", 400));
        }
        return new OpenRouterKeyStore(builder.build(), directory.toString());
    }
}
