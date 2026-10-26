package com.sakshichavan.rtb.bidder;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BidderStatsEndpointTest {

    @Test
    void statsEndpointServesTheSuppliedCountersAsJson() throws Exception {
        try (BidderServer server = new BidderServer(0, request -> Optional.empty(), () -> Map.of("bidsSeen", 42));
             HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/stats")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"bidsSeen\":42"), response.body());
        }
    }
}
