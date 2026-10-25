package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;
import com.sakshichavan.rtb.common.openrtb.Imp;
import com.sakshichavan.rtb.common.openrtb.OpenRtbJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BidderServerTest {

    /** Test engine: bids floor + 0.05 when the floor is under 1.00, no-bids otherwise, throws on id "boom". */
    private static final BidEngine TEST_ENGINE = request -> {
        if (request.id().equals("boom")) {
            throw new IllegalStateException("simulated engine bug");
        }
        Imp imp = request.imp().get(0);
        if (imp.bidFloor() >= 1.00) {
            return Optional.empty();
        }
        Bid bid = new Bid("bid-1", imp.id(), imp.bidFloor() + 0.05, "ad-1", "cr-1", null);
        return Optional.of(BidResponse.single(request.id(), "seat-1", bid));
    };

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private BidderServer server;

    @BeforeEach
    void startServer() throws InterruptedException {
        server = new BidderServer(0, TEST_ENGINE);
    }

    @AfterEach
    void stopServer() {
        server.close();
        client.close();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private HttpResponse<byte[]> post(String path, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static BidRequest bidRequest(String id, double floor) {
        Imp imp = new Imp("imp-1", new Banner(300, 250), floor, "USD");
        return new BidRequest(id, List.of(imp), null, null, null, 100, null);
    }

    @Test
    void healthEndpointReturnsOk() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/health")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
    }

    @Test
    void returnsValidBidWhenEngineBids() throws Exception {
        BidRequest request = bidRequest("req-1", 0.50);

        HttpResponse<byte[]> response = post("/bid", OpenRtbJson.write(request));

        assertEquals(200, response.statusCode());
        BidResponse bidResponse = OpenRtbJson.readResponse(response.body());
        assertTrue(bidResponse.isValidFor(request));
    }

    @Test
    void returns204WhenEngineDoesNotBid() throws Exception {
        HttpResponse<byte[]> response = post("/bid", OpenRtbJson.write(bidRequest("req-2", 2.00)));

        assertEquals(204, response.statusCode());
        assertEquals(0, response.body().length);
    }

    @Test
    void returns400ForMalformedJson() throws Exception {
        HttpResponse<byte[]> response = post("/bid", "{not json".getBytes(StandardCharsets.UTF_8));

        assertEquals(400, response.statusCode());
    }

    @Test
    void returns500WhenEngineThrowsAndKeepsServing() throws Exception {
        HttpResponse<byte[]> failed = post("/bid", OpenRtbJson.write(bidRequest("boom", 0.50)));
        HttpResponse<byte[]> next = post("/bid", OpenRtbJson.write(bidRequest("req-3", 0.50)));

        assertEquals(500, failed.statusCode());
        assertEquals(200, next.statusCode());
    }

    @Test
    void returns405ForWrongMethod() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/bid")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
    }

    @Test
    void returns404ForUnknownPath() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/nope")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
    }
}
