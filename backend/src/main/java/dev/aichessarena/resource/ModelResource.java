package dev.aichessarena.resource;

import dev.aichessarena.dto.PromptTemplateDto;
import dev.aichessarena.service.OpenRouterService;
import dev.aichessarena.service.PromptService;
import jakarta.inject.Inject;
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
    PromptService promptService;

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
        boolean valid = openRouterService.checkApiKey();
        return Response.ok("{\"valid\": " + valid + "}").build();
    }

    @GET
    @Path("/config/prompt-template")
    public Response getPromptTemplate() {
        String template = promptService.getDefaultSystemPromptTemplate();
        PromptTemplateDto dto = new PromptTemplateDto(
                template,
                promptService.getDefaultSystemPromptVersion(),
                promptService.computePromptHash(template)
        );
        return Response.ok(dto).build();
    }
}
