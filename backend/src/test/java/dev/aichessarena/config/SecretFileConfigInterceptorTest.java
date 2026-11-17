package dev.aichessarena.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretFileConfigInterceptorTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"DB_PASSWORD", "OPENROUTER_API_KEY"})
    void trimsFileContentsAndResolvesApplicationExpressions(String name) throws Exception {
        Path file = Files.writeString(directory.resolve("secret"), " \tfile-secret\r\n");
        var source = source(Map.of(name + "_FILE", file.toString(), name, ""));
        SmallRyeConfig config = config(source, Map.of(property(name), "${" + name + "}"));
        assertEquals("file-secret", source.resolve(name));
        assertEquals("file-secret", config.getValue(property(name), String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DB_PASSWORD", "OPENROUTER_API_KEY"})
    void explicitEnvironmentWinsWithoutReadingInvalidFile(String name) {
        var source = source(Map.of(name, " explicit$env ", name + "_FILE", "invalid\0path"));
        assertEquals(" explicit$env ", source.resolve(name));
        assertEquals(" explicit$env ", config(source, Map.of(property(name), "${" + name + "}"))
                .getValue(property(name), String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "literal${key}$value"})
    void realEnvironmentAliasesPreserveFileFallbackAndLiteralExplicitKeys(String explicit) throws Exception {
        Path file = Files.writeString(directory.resolve("api-key"), "file-key\n");
        Map<String, String> environment = Map.of("OPENROUTER_API_KEY", explicit,
                "OPENROUTER_API_KEY_FILE", explicit.isEmpty() ? file.toString() : "missing-file");
        SmallRyeConfig config = new SmallRyeConfigBuilder().addDefaultInterceptors()
                .withInterceptors(source(environment))
                .withSources(new EnvConfigSource(environment, 300), new PropertiesConfigSource(
                        Map.of("openrouter.api-key", "${OPENROUTER_API_KEY:}"), "application", 250)).build();
        assertEquals(explicit.isEmpty() ? "file-key" : explicit, config.getValue("openrouter.api-key", String.class));
    }

    @Test
    void noFileKeepsExistingDefaultsAndUnrelatedConfiguration() {
        var source = source(Map.of());
        assertNull(source.resolve("DB_PASSWORD"));
        assertNull(source.resolve("INSTANCE_KEY_FILE"));
        assertEquals("dev-default", config(source, Map.of("quarkus.datasource.password", "${DB_PASSWORD:dev-default}"))
                .getValue("quarkus.datasource.password", String.class));
    }

    @Test
    void configuredFileWinsOverFallbackButSystemPropertiesCanStillOverride() throws Exception {
        Path file = Files.writeString(directory.resolve("password"), "file-password");
        var source = source(Map.of("DB_PASSWORD_FILE", file.toString()));
        assertEquals("file-password", config(source, Map.of("quarkus.datasource.password", "${DB_PASSWORD:fallback}"))
                .getValue("quarkus.datasource.password", String.class));
        SmallRyeConfig config = new SmallRyeConfigBuilder().addDefaultInterceptors().withInterceptors(source).withSources(
                new PropertiesConfigSource(Map.of("quarkus.datasource.password", "system-override"), "system", 400)).build();
        assertEquals("system-override", config.getValue("quarkus.datasource.password", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DB_PASSWORD", "OPENROUTER_API_KEY"})
    void rejectsMissingDirectoryEmptyAndInvalidPathsWithoutLeakingDetails(String name) throws Exception {
        Path empty = Files.writeString(directory.resolve("sensitive-file-name"), " \n\t");
        for (String path : new String[]{empty.toString(), directory.toString(),
                directory.resolve("sensitive-missing-name").toString(), "sensitive\0invalid"}) {
            var source = source(Map.of(name + "_FILE", path));
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> source.resolve(name));
            assertEquals(name + "_FILE must name a readable, non-empty secret file", error.getMessage());
            assertNull(error.getCause());
            assertFalse(error.toString().contains("sensitive"));
        }
    }

    @Test
    void secretValuesAreNotWrittenToOutputOrSourceMetadata() throws Exception {
        Path file = Files.writeString(directory.resolve("secret"), "private-test-value");
        var source = source(Map.of("DB_PASSWORD_FILE", file.toString()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            System.setErr(capture);
            assertEquals("private-test-value", config(source, Map.of("quarkus.datasource.password", "${DB_PASSWORD}"))
                    .getValue("quarkus.datasource.password", String.class));
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("private-test-value"));
        assertFalse(source.toString().contains("private-test-value"));
    }

    @Test
    void discoveredAsAConfigInterceptor() {
        assertTrue(ServiceLoader.load(ConfigSourceInterceptor.class).stream()
                .anyMatch(provider -> provider.type() == SecretFileConfigInterceptor.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"literal${not.a.property}$value", "back\\${expression}", "two$$dollars", "plain"})
    void literalDollarExpressionsInFilesRemainLiteral(String value) throws Exception {
        Path file = Files.writeString(directory.resolve("secret"), value);
        var source = source(Map.of("DB_PASSWORD_FILE", file.toString()));
        assertEquals(value, config(source, Map.of("quarkus.datasource.password", "${DB_PASSWORD}"))
                .getValue("quarkus.datasource.password", String.class));
    }

    @Test
    void unreadableFilesFailClosed() throws Exception {
        Path file = Files.writeString(directory.resolve("unreadable"), "private-value");
        Files.setPosixFilePermissions(file, java.util.Set.of());
        try {
            var source = source(Map.of("DB_PASSWORD_FILE", file.toString()));
            assertThrows(IllegalStateException.class, () -> source.resolve("DB_PASSWORD"));
        } finally {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
    }

    private String property(String name) {
        return name.equals("DB_PASSWORD") ? "quarkus.datasource.password" : "openrouter.api-key";
    }

    private SecretFileConfigInterceptor source(Map<String, String> environment) {
        return new SecretFileConfigInterceptor(environment::get);
    }

    private SmallRyeConfig config(ConfigSourceInterceptor source, Map<String, String> properties) {
        return new SmallRyeConfigBuilder().addDefaultInterceptors().withInterceptors(source).withSources(
                new PropertiesConfigSource(properties, "test", 300)).build();
    }
}
