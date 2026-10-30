package com.sakshichavan.rtb.campaigns;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class CampaignApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static CampaignStore store;
    private static CampaignApi api;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        store = new CampaignStore(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        api = new CampaignApi(0, store);
    }

    @AfterAll
    static void stop() {
        api.close();
        store.close();
    }

    private static String body(String id, double price) {
        return """
                {"id": "%s", "creativeId": "cr-1", "maxBidCpm": %s, "dailyBudgetUsd": 10.0,
                 "targeting": {"countries": ["USA"]}}""".formatted(id, price);
    }

    private static HttpResponse<String> put(String id, String json, String header, String value) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + "/campaigns/" + id))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json));
        if (header != null) {
            request.header(header, value);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void createReadAndUpdateWithOptimisticConcurrency() throws Exception {
        HttpResponse<String> created = put("c-api", body("c-api", 1.00), "If-None-Match", "*");
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("\"1\"", created.headers().firstValue("ETag").orElseThrow());

        assertEquals(412, put("c-api", body("c-api", 1.00), "If-None-Match", "*").statusCode(), "create twice");
        assertEquals(428, put("c-api", body("c-api", 2.00), null, null).statusCode(), "update without a precondition");

        HttpResponse<String> updated = put("c-api", body("c-api", 2.00), "If-Match", "\"1\"");
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("\"2\"", updated.headers().firstValue("ETag").orElseThrow());

        HttpResponse<String> stale = put("c-api", body("c-api", 3.00), "If-Match", "\"1\"");
        assertEquals(412, stale.statusCode(), "a second editor still holding version 1 must be rejected");
        assertEquals("\"2\"", stale.headers().firstValue("ETag").orElseThrow(), "and told the current version");

        HttpResponse<String> current = get("/campaigns/c-api");
        assertEquals(200, current.statusCode());
        assertTrue(current.body().contains("\"maxBidCpm\":2.0"), current.body());
        assertEquals(2, store.changesAfter(0, 100).size());
    }

    @Test
    void invalidRequestsAreRejectedWithReasons() throws Exception {
        HttpResponse<String> invalid = put("c-bad", """
                {"id": "c-bad", "creativeId": "cr-1", "maxBidCpm": -1, "dailyBudgetUsd": 0}""", "If-None-Match", "*");
        assertEquals(400, invalid.statusCode());
        assertTrue(invalid.body().contains("maxBidCpm") && invalid.body().contains("dailyBudgetUsd"), invalid.body());

        HttpResponse<String> misspelled = put("c-typo", """
                {"id": "c-typo", "creativeId": "cr-1", "maxBidCpm": 1.0, "dailyBudget": 10.0}""", "If-None-Match", "*");
        assertEquals(400, misspelled.statusCode(), "an unknown field must be an error, not silently ignored");

        assertEquals(400, put("c-one", body("c-other", 1.00), "If-None-Match", "*").statusCode(),
                "the body's id must match the URL");
        assertEquals(404, get("/campaigns/c-nope").statusCode());
    }
}
