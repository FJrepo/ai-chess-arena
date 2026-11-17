package dev.aichessarena.resource;

import static org.junit.jupiter.api.Assertions.*;

import dev.aichessarena.config.OpenRouterKeyStoreFixture;
import dev.aichessarena.service.StockfishService;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelResourceTest {
    @TempDir Path directory;

    @Test
    void systemStatusIncludesStockfishAvailabilityAndUntestedKeyMetadata() {
        ModelResource resource = new ModelResource();
        resource.keys = OpenRouterKeyStoreFixture.keys("test-provider-key", directory);
        resource.stockfishService = new UnavailableStockfishService();
        resource.applicationVersion = "0.2.1";

        Response response = resource.getSystemStatus();
        var body = (ModelResource.SystemStatusResponse) response.getEntity();

        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertEquals("0.2.1", body.backendVersion());
        assertFalse(body.openRouterValid());
        assertEquals("env", body.key().source());
        assertEquals("••••-key", body.key().maskedSuffix());
        assertEquals("unknown", body.key().validation());
        assertNull(body.key().lastValidatedAt());
        assertFalse(body.stockfishAvailable());
        assertEquals("Stockfish missing", body.stockfishReason());
        assertNotNull(body.checkedAt());
    }

    @Test
    void legacyStatusReportsMissingKeyWithoutProviderService() {
        var resource = new ModelResource();
        resource.keys = OpenRouterKeyStoreFixture.keys(null, directory);
        var response = resource.checkStatus();
        var body = (ModelResource.OpenRouterStatusResponse) response.getEntity();
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertFalse(body.valid());
        assertEquals("none", body.key().source());
        assertEquals("none", body.key().validation());
        assertNull(body.key().maskedSuffix());
        assertNull(body.key().lastValidatedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "file", "ui"})
    void untestedKeysRemainConfiguredInBothPassiveDtos(String source) throws Exception {
        var resource = new ModelResource();
        String fixtureKey = "test-provider-key";
        if (source.equals("env")) {
            resource.keys = OpenRouterKeyStoreFixture.keys(null, directory, Map.of("OPENROUTER_API_KEY", fixtureKey));
        } else {
            Path file = directory.resolve(source.equals("ui") ? "openrouter_key" : "operator_key");
            Files.writeString(file, fixtureKey);
            resource.keys = OpenRouterKeyStoreFixture.keys(null, directory, source.equals("file")
                    ? Map.of("OPENROUTER_API_KEY_FILE", file.toString()) : Map.of());
        }
        resource.stockfishService = new UnavailableStockfishService();

        var status = (ModelResource.OpenRouterStatusResponse) resource.checkStatus().getEntity();
        var system = (ModelResource.SystemStatusResponse) resource.getSystemStatus().getEntity();

        assertFalse(status.valid(), "Legacy flag reports validation, not key presence");
        assertFalse(system.openRouterValid());
        assertEquals(source, status.key().source());
        assertEquals("unknown", status.key().validation());
        assertEquals("••••-key", status.key().maskedSuffix());
        assertNull(status.key().lastValidatedAt());
        assertEquals(status.key(), system.key());
    }

    private static final class UnavailableStockfishService extends StockfishService {
        @Override
        public Status status() {
            return new Status(false, "Stockfish missing");
        }
    }
}
