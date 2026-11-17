package dev.aichessarena.resource;

import dev.aichessarena.dto.PromptTemplateDto;
import dev.aichessarena.entity.BanterLevel;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.service.OpenRouterService;
import dev.aichessarena.service.OpenRouterKeyStore;
import dev.aichessarena.service.PromptService;
import dev.aichessarena.service.StockfishService;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class ModelResource {

    @Inject
    OpenRouterService openRouterService;

    @Inject
    OpenRouterKeyStore keys;

    @Inject
    PromptService promptService;

    @Inject
    StockfishService stockfishService;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
    String applicationVersion;

    /**
     * Text-output OpenRouter models. {@code limit} defaults to 120 and is capped at 300; {@code limit=0} returns
     * every matching model (the setup page loads the whole catalog once and filters it client-side).
     */
    @GET
    @Path("/models")
    public Response listModels(
            @QueryParam("featuredOnly") @DefaultValue("true") boolean featuredOnly,
            @QueryParam("q") String query,
            @QueryParam("provider") String provider,
            @QueryParam("limit") @DefaultValue("120") int limit
    ) {
        var models = openRouterService.listModels(featuredOnly, query, limit, provider);
        return Response.ok(models).build();
    }

    @GET
    @Path("/config/openrouter-status")
    public Response checkStatus() {
        var key = keys.status();
        return Response.ok(new OpenRouterStatusResponse("valid".equals(key.validation()), key))
                .header("Cache-Control", "no-store").build();
    }

    @GET
    @Path("/config/prompt-template")
    public Response getPromptTemplate() {
        String template = promptService.getDefaultSystemPromptTemplate();
        PromptTemplateDto dto = new PromptTemplateDto(
                template,
                promptService.getDefaultSystemPromptVersion(),
                promptService.computePromptHash(template),
                promptVariants(false),
                promptVariants(true),
                banterVariants(false),
                banterVariants(true),
                new PromptTemplateDto.NoHintsVariant(PromptService.NO_HINTS_RULE, PromptService.NO_HINTS_VERSION_SUFFIX)
        );
        return Response.ok(dto).build();
    }

    /** The rules of every chat mode, with or without resignation, by mode name. */
    private java.util.Map<String, PromptTemplateDto.Variant> promptVariants(boolean resignationAllowed) {
        return promptVariants(resignationAllowed, null);
    }

    private java.util.Map<String, java.util.Map<String, PromptTemplateDto.Variant>> banterVariants(boolean resign) {
        java.util.Map<String, java.util.Map<String, PromptTemplateDto.Variant>> levels = new java.util.LinkedHashMap<>();
        for (BanterLevel level : BanterLevel.values()) {
            levels.put(level.name(), promptVariants(resign, level));
        }
        return levels;
    }

    private java.util.Map<String, PromptTemplateDto.Variant> promptVariants(boolean resignationAllowed,
                                                                          BanterLevel level) {
        java.util.Map<String, PromptTemplateDto.Variant> variants = new java.util.LinkedHashMap<>();
        for (ChatMode mode : ChatMode.values()) {
            String modeTemplate = promptService.getSystemPromptTemplate(mode, resignationAllowed, level);
            variants.put(mode.name(), new PromptTemplateDto.Variant(
                    modeTemplate,
                    promptService.getSystemPromptVersion(mode, resignationAllowed, level),
                    promptService.computePromptHash(modeTemplate)));
        }
        return variants;
    }

    @GET
    @Path("/config/system-status")
    public Response getSystemStatus() {
        var stockfish = stockfishService.status();
        var key = keys.status();
        SystemStatusResponse dto = new SystemStatusResponse(
                applicationVersion,
                "valid".equals(key.validation()),
                stockfish.available(),
                stockfish.reason(),
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString(),
                key
        );
        return Response.ok(dto).header("Cache-Control", "no-store").build();
    }

    /** Legacy valid flag describes validation only; key.source determines connection, even when untested. */
    public record OpenRouterStatusResponse(boolean valid, OpenRouterKeyStore.Status key) {}

    /** openRouterValid is validation-only; false also includes unknown/unavailable checks, not just invalid keys. */
    public record SystemStatusResponse(
            String backendVersion,
            boolean openRouterValid,
            boolean stockfishAvailable,
            String stockfishReason,
            String checkedAt,
            OpenRouterKeyStore.Status key
    ) {}
}
