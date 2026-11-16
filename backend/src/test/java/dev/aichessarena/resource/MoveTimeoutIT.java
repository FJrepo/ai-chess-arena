package dev.aichessarena.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The optional move time limit (migration V11) through the REST API against a real PostgreSQL. */
@QuarkusTest
class MoveTimeoutIT {

    @Inject
    EntityManager entityManager;

    @Test
    void migrationV11MakesTheMoveTimeoutNullable() {
        String nullable = QuarkusTransaction.requiringNew().call(() -> (String) entityManager.createNativeQuery(
                "select is_nullable from information_schema.columns "
                        + "where table_name = 'tournaments' and column_name = 'move_timeout_seconds'")
                .getSingleResult());
        assertEquals("YES", nullable);
        Number applied = QuarkusTransaction.requiringNew().call(() -> (Number) entityManager.createNativeQuery(
                "select count(*) from flyway_schema_history where version = '11' and success")
                .getSingleResult());
        assertEquals(1, applied.intValue());
    }

    @Test
    void noLimitRoundTripsThroughCreateGetAndUpdate() {
        String id = given().contentType("application/json")
                .body("{\"name\":\"No limit " + UUID.randomUUID() + "\",\"moveTimeoutSeconds\":0}")
                .when().post("/api/tournaments")
                .then().statusCode(201)
                .body("moveTimeoutSeconds", nullValue())
                .extract().path("id");

        given().when().get("/api/tournaments/{id}", id)
                .then().statusCode(200)
                .body("$", hasKey("moveTimeoutSeconds"))
                .body("moveTimeoutSeconds", nullValue());
        assertNull(storedTimeout(id));

        // An update without the field leaves "no limit" unchanged.
        given().contentType("application/json").body("{\"maxRetries\":2}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(200)
                .body("moveTimeoutSeconds", nullValue());

        given().contentType("application/json").body("{\"moveTimeoutSeconds\":45}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(200)
                .body("moveTimeoutSeconds", equalTo(45));
        assertEquals(45, storedTimeout(id));
    }

    @Test
    void omittedOrNullTimeoutDefaultsToNoLimitAndOutOfRangeValuesAreRejected() {
        String omitted = given().contentType("application/json")
                .body("{\"name\":\"Default " + UUID.randomUUID() + "\"}")
                .when().post("/api/tournaments")
                .then().statusCode(201)
                .body("moveTimeoutSeconds", nullValue())
                .extract().path("id");
        assertNull(storedTimeout(omitted));

        String explicitNull = given().contentType("application/json")
                .body("{\"name\":\"Null " + UUID.randomUUID() + "\",\"moveTimeoutSeconds\":null}")
                .when().post("/api/tournaments")
                .then().statusCode(201)
                .body("moveTimeoutSeconds", nullValue())
                .extract().path("id");
        assertNull(storedTimeout(explicitNull));

        given().contentType("application/json")
                .body("{\"name\":\"Too short " + UUID.randomUUID() + "\",\"moveTimeoutSeconds\":5}")
                .when().post("/api/tournaments")
                .then().statusCode(400)
                .body(containsString("moveTimeoutSeconds"));
    }

    private Integer storedTimeout(String id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object value = entityManager.createNativeQuery(
                            "select move_timeout_seconds from tournaments where id = cast(?1 as uuid)")
                    .setParameter(1, id)
                    .getSingleResult();
            return value == null ? null : ((Number) value).intValue();
        });
    }
}
