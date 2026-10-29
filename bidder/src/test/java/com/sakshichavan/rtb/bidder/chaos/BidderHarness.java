package com.sakshichavan.rtb.bidder.chaos;

import com.sakshichavan.rtb.bidder.BidPipeline;
import com.sakshichavan.rtb.bidder.BidPricer;
import com.sakshichavan.rtb.bidder.BidderServer;
import com.sakshichavan.rtb.bidder.TargetingBidEngine;
import com.sakshichavan.rtb.bidder.budget.SpendGuard;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.Targeting;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.bidder.frequency.FrequencyGuard;
import com.sakshichavan.rtb.bidder.profile.ProfileStore;
import com.sakshichavan.rtb.bidder.resilience.CircuitBreaker;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Imp;
import com.sakshichavan.rtb.common.openrtb.OpenRtbJson;
import com.sakshichavan.rtb.common.openrtb.User;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only: a real bidder (Netty server + async pipeline + engine) wired to whatever stores a chaos test
 * provides, plus a client that sends bursts of requests and records what came back.
 * Two campaigns: an audience campaign that needs a "traveler" profile, and a broad fallback that needs nothing.
 */
final class BidderHarness implements AutoCloseable {

    static final Campaign BROAD = new Campaign("broad", "cr-broad", 1.00, 10.0, null);
    static final Campaign TRAVEL_AUDIENCE = new Campaign("travel-audience", "cr-travel", 2.00, 10.0,
            new Targeting(null, null, null, Set.of("traveler")));
    static final List<Campaign> CAMPAIGNS = List.of(BROAD, TRAVEL_AUDIENCE);

    /** What one burst of requests produced. Every request is either answered (200 or 204) or a failure. */
    record Burst(int answered, int bids, int audienceBids, int failures, long maxLatencyMillis) {
    }

    final BidPipeline pipeline;
    private final BidderServer server;
    private final HttpClient client;
    private final AtomicLong sequence = new AtomicLong();

    BidderHarness(ProfileStore profiles, SpendGuard spend, CircuitBreaker breaker) throws InterruptedException {
        TargetingBidEngine engine = new TargetingBidEngine(
                new TargetingIndex(CAMPAIGNS), new BidPricer(0.5), spend, FrequencyGuard.UNLIMITED);
        this.pipeline = new BidPipeline(profiles, engine, 5, breaker);
        this.server = BidderServer.withAsyncEngine(0, pipeline, () -> Map.of());
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    /** Sends requests one after another for 50 distinct users, measuring each round trip. */
    Burst send(int count) {
        URI uri = URI.create("http://127.0.0.1:" + server.port() + "/bid");
        int answered = 0;
        int bids = 0;
        int audienceBids = 0;
        int failures = 0;
        long maxNanos = 0;

        for (int i = 0; i < count; i++) {
            BidRequest request = new BidRequest("chaos-" + sequence.incrementAndGet(),
                    List.of(new Imp("1", new Banner(300, 250), 0.10, "USD")),
                    null, null, new User("user-" + (i % 50)), 100, null);
            HttpRequest http = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(OpenRtbJson.write(request)))
                    .build();

            long start = System.nanoTime();
            try {
                HttpResponse<byte[]> response = client.send(http, HttpResponse.BodyHandlers.ofByteArray());
                maxNanos = Math.max(maxNanos, System.nanoTime() - start);
                if (response.statusCode() == 204) {
                    answered++;
                } else if (response.statusCode() == 200) {
                    answered++;
                    bids++;
                    String adId = OpenRtbJson.readResponse(response.body()).seatBid().get(0).bid().get(0).adId();
                    if (TRAVEL_AUDIENCE.id().equals(adId)) {
                        audienceBids++;
                    }
                } else {
                    failures++;
                }
            } catch (IOException e) {
                failures++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures++;
            }
        }
        return new Burst(answered, bids, audienceBids, failures, TimeUnit.NANOSECONDS.toMillis(maxNanos));
    }

    @Override
    public void close() {
        server.close();
        pipeline.close();
        client.close();
    }
}
