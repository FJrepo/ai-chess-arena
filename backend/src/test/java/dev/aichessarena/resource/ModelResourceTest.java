package dev.aichessarena.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.aichessarena.service.OpenRouterService;
import dev.aichessarena.service.PromptService;
import dev.aichessarena.service.StockfishService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

class ModelResourceTest {

    @Test
    void systemStatusIncludesStockfishAvailability() {
        ModelResource resource = new ModelResource();
        resource.openRouterService = new AlwaysValidOpenRouterService();
        resource.promptService = new PromptService();
        resource.stockfishService = new UnavailableStockfishService();
        resource.applicationVersion = "0.2.1";

        Response response = resource.getSystemStatus();
        ModelResource.SystemStatusResponse body =
                (ModelResource.SystemStatusResponse) response.getEntity();

        assertEquals(200, response.getStatus());
        assertEquals("0.2.1", body.backendVersion());
        assertEquals(true, body.openRouterValid());
        assertEquals(false, body.stockfishAvailable());
        assertEquals("Stockfish missing", body.stockfishReason());
        assertEquals(true, body.checkedAt() != null && !body.checkedAt().isBlank());
    }

    private static final class AlwaysValidOpenRouterService extends OpenRouterService {
        @Override
        public boolean checkApiKey() {
            return true;
        }
    }

    private static final class UnavailableStockfishService extends StockfishService {
        @Override
        public Status status() {
            return new Status(false, "Stockfish missing");
        }
    }
}
