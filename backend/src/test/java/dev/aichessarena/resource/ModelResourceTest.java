package dev.aichessarena.resource;

import static org.junit.jupiter.api.Assertions.*;

import dev.aichessarena.config.OpenRouterKeyStoreFixture;
import dev.aichessarena.service.StockfishService;
import jakarta.ws.rs.core.Response;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private static final class UnavailableStockfishService extends StockfishService {
        @Override
        public Status status() {
            return new Status(false, "Stockfish missing");
        }
    }
}
