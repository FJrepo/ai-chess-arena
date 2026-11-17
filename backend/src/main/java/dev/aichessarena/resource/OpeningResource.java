package dev.aichessarena.resource;

import dev.aichessarena.service.OpeningBookService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/openings")
@Produces(MediaType.APPLICATION_JSON)
public class OpeningResource {
    @Inject OpeningBookService book;

    @GET
    public List<OpeningBookService.Opening> list() {
        return book.list();
    }
}
