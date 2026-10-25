package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;
import com.sakshichavan.rtb.common.openrtb.Imp;
import com.sakshichavan.rtb.common.openrtb.OpenRtbJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Minimal fake bidder for testing the simulator before the real bidder exists.
 * Waits a fixed delay, then bids (floor + 0.10) with probability bidRate, else returns 204 no-bid.
 */
public final class StubBidder implements AutoCloseable {

    private final HttpServer server;
    private final Duration delay;
    private final double bidRate;

    public StubBidder(int port, Duration delay, double bidRate) throws IOException {
        this.delay = delay;
        this.bidRate = bidRate;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/bid", this::handle);
        this.server.start();
    }

    public URI bidUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/bid");
    }

    private void handle(HttpExchange exchange) {
        try {
            BidRequest request = OpenRtbJson.readRequest(exchange.getRequestBody().readAllBytes());
            if (!delay.isZero()) {
                Thread.sleep(delay);
            }
            if (ThreadLocalRandom.current().nextDouble() < bidRate) {
                Imp imp = request.imp().get(0);
                Bid bid = new Bid("bid-" + request.id(), imp.id(), imp.bidFloor() + 0.10, "ad-1", "cr-1", null);
                byte[] json = OpenRtbJson.write(BidResponse.single(request.id(), "stub-seat", bid));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.length);
                exchange.getResponseBody().write(json);
            } else {
                exchange.sendResponseHeaders(204, -1);
            }
        } catch (UncheckedIOException e) {
            sendQuietly(exchange, 400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Client disconnected (for example, it timed out). Nothing to send back.
        } finally {
            exchange.close();
        }
    }

    private static void sendQuietly(HttpExchange exchange, int status) {
        try {
            exchange.sendResponseHeaders(status, -1);
        } catch (IOException ignored) {
            // Client already gone.
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
