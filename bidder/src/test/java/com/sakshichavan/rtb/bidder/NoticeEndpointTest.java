package com.sakshichavan.rtb.bidder;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoticeEndpointTest {

    private static int get(HttpClient client, BidderServer server, String pathAndQuery) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + server.port() + pathAndQuery);
        return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    @Test
    void routesValidNoticesToTheSinkAndRejectsMalformedOnes() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        NoticeSink recording = new NoticeSink() {
            @Override
            public void win(String bidId, double clearingPriceCpm) {
                received.add("win " + bidId + " " + clearingPriceCpm);
            }

            @Override
            public void loss(String bidId) {
                received.add("loss " + bidId);
            }
        };

        try (BidderServer server = BidderServer.withAsyncEngine(0, 0,
                request -> CompletableFuture.completedFuture(Optional.empty()), recording, () -> Map.of());
             HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {

            assertEquals(204, get(client, server, "/win?bid=b1&price=1.25"));
            assertEquals(204, get(client, server, "/loss?bid=b2"));
            assertEquals(400, get(client, server, "/win?bid=b3&price=%24%7BAUCTION_PRICE%7D"));
            assertEquals(400, get(client, server, "/win?price=1.0"));
            assertEquals(400, get(client, server, "/win?bid=b4&price=NaN"));

            assertEquals(List.of("win b1 1.25", "loss b2"), received);
        }
    }
}
