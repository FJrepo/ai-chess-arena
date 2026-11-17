package dev.aichessarena.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TournamentAttemptsIT {

    @Test
    void createAndUpdateAcceptBothBoundariesAndPreserveOmittedLimits() {
        String id = given().contentType("application/json").body("{\"name\":\"Attempts\",\"maxRetries\":1}")
                .post("/api/tournaments").then().statusCode(201)
                .body("maxRetries", equalTo(1)).extract().path("id");
        given().contentType("application/json").body("{\"maxRetries\":20}")
                .put("/api/tournaments/{id}", id).then().statusCode(200).body("maxRetries", equalTo(20));
        for (String body : List.of("{}", "{\"maxRetries\":null}")) {
            given().contentType("application/json").body(body)
                    .put("/api/tournaments/{id}", id).then().statusCode(200).body("maxRetries", equalTo(20));
        }
    }

    @Test
    void invalidAttemptsFailClosedOnCreateAndUpdate() {
        String id = given().contentType("application/json").body("{\"name\":\"Original\"}")
                .post("/api/tournaments").then().statusCode(201)
                .body("maxRetries", equalTo(3)).extract().path("id");
        String message = "maxRetries must be a whole number from 1 to 20";
        for (String invalid : List.of("0", "-1", "21", "1.5", "\"\"", "2147483649")) {
            String body = "{\"name\":\"Invalid\",\"maxRetries\":" + invalid + "}";
            given().contentType("application/json").body(body).post("/api/tournaments")
                    .then().statusCode(400).body("error", equalTo(message));
            given().contentType("application/json").body(body).put("/api/tournaments/{id}", id)
                    .then().statusCode(400).body("error", equalTo(message));
        }
        given().get("/api/tournaments/{id}", id).then().statusCode(200)
                .body("maxRetries", equalTo(3)).body("name", equalTo("Original"));
    }
}
