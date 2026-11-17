package dev.aichessarena.config;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.WebApplicationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenRouterKeyStoreConfigTest {
    @TempDir Path directory;

    @Test
    void systemPropertyWinsOverEnvironmentAndFileWithoutReadingLosingFile() throws Exception {
        Files.writeString(directory.resolve("openrouter_key"), "test-ui-key");
        var keys = OpenRouterKeyStoreFixture.keys("test-property-key", directory,
                Map.of("OPENROUTER_API_KEY", "test-env-key", "OPENROUTER_API_KEY_FILE", "invalid\0file"));
        assertEquals("test-property-key", keys.key());
        assertEquals("env", keys.status().source());
        assertEquals(409, assertThrows(WebApplicationException.class, keys::delete).getResponse().getStatus());
        var withoutEnv = OpenRouterKeyStoreFixture.keys("test-property-key", directory,
                Map.of("OPENROUTER_API_KEY_FILE", "invalid\0file"));
        assertEquals("test-property-key", withoutEnv.key());
        assertEquals("env", withoutEnv.status().source());
    }

    @Test
    void environmentWinsOverFileWithoutReadingLosingFile() throws Exception {
        Files.writeString(directory.resolve("openrouter_key"), "test-ui-key");
        var keys = OpenRouterKeyStoreFixture.keys(null, directory,
                Map.of("OPENROUTER_API_KEY", "test-env-key", "OPENROUTER_API_KEY_FILE", "invalid\0file"));
        assertEquals("test-env-key", keys.key());
        assertEquals("env", keys.status().source());
    }

    @Test
    void fileWinsOverUiAndIsRereadOnEachRequest() throws Exception {
        Files.writeString(directory.resolve("openrouter_key"), "test-ui-key");
        Path file = Files.writeString(directory.resolve("operator"), "  test-file-key\n");
        var keys = OpenRouterKeyStoreFixture.keys(null, directory,
                Map.of("OPENROUTER_API_KEY", "", "OPENROUTER_API_KEY_FILE", file.toString()));
        assertEquals("test-file-key", keys.key());
        assertEquals("file", keys.status().source());
        Files.writeString(file, "test-rotated-key");
        assertEquals("test-rotated-key", keys.key());
        assertEquals("file", keys.status().source());
        Files.delete(file);
        assertThrows(IllegalStateException.class, keys::key);
    }

    @Test
    void uiOnlyAndMissingKeyRemainDynamic() throws Exception {
        var keys = OpenRouterKeyStoreFixture.keys(null, directory);
        assertEquals("none", keys.source());
        Files.writeString(directory.resolve("openrouter_key"), "test-ui-key");
        assertEquals("test-ui-key", keys.key());
        assertEquals("ui", keys.status().source());
        keys.delete();
        assertEquals("none", keys.status().source());
    }
}
