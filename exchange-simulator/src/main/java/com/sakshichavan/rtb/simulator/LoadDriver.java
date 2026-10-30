package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.money.Micros;
import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;
import com.sakshichavan.rtb.common.openrtb.OpenRtbJson;
import com.sakshichavan.rtb.common.openrtb.SeatBid;
import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop load driver: sends requests on a fixed schedule regardless of how the bidders respond.
 * A Router picks the bidder for each request, like the load balancer in front of a fleet.
 * Records response time (from the scheduled send, avoiding coordinated omission), service time
 * (from the actual send), and per-user frequency ground truth for audited campaigns.
 * With an AuctionHouse, it also runs each auction, calls the bid's win or loss notice URL, and draws clicks.
 */
public final class LoadDriver implements AutoCloseable {

    private static final long MAX_LATENCY_MICROS = TimeUnit.SECONDS.toMicros(10);
    private static final Duration NOTICE_DRAIN_TIMEOUT = Duration.ofSeconds(10);

    private final Router router;
    private final Duration timeoutOverride;
    private final AuctionHouse auctionHouse;
    private final HttpClient client;

    /** Single bidder; enforces each request's tmax as its deadline, like a real exchange. */
    public LoadDriver(URI target) {
        this(Router.single(target), null, null);
    }

    public LoadDriver(URI target, Duration timeoutOverride) {
        this(Router.single(target), timeoutOverride, null);
    }

    public LoadDriver(Router router, Duration timeoutOverride) {
        this(router, timeoutOverride, null);
    }

    /**
     * @param timeoutOverride if non-null, used instead of each request's tmax
     * @param auctionHouse    if non-null, auctions are simulated and notices sent to the bidders
     */
    public LoadDriver(Router router, Duration timeoutOverride, AuctionHouse auctionHouse) {
        this.router = router;
        this.timeoutOverride = timeoutOverride;
        this.auctionHouse = auctionHouse;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public LoadReport run(TrafficGenerator generator, int qps, Duration duration) throws InterruptedException {
        return run(generator, qps, duration, Map.of());
    }

    /** @param capsToAudit campaign id to frequency cap; these campaigns get per-user ground truth in the report */
    public LoadReport run(TrafficGenerator generator, int qps, Duration duration, Map<String, Integer> capsToAudit)
            throws InterruptedException {
        if (qps <= 0) {
            throw new IllegalArgumentException("qps must be positive: " + qps);
        }
        long totalRequests = qps * duration.toSeconds();
        if (totalRequests <= 0 || totalRequests > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid total request count: " + totalRequests);
        }
        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / qps;

        Histogram responseTime = new ConcurrentHistogram(MAX_LATENCY_MICROS, 3);
        Histogram serviceTime = new ConcurrentHistogram(MAX_LATENCY_MICROS, 3);
        Outcomes outcomes = new Outcomes();
        Market market = new Market();
        CountDownLatch completed = new CountDownLatch((int) totalRequests);

        long start = System.nanoTime();
        for (long i = 0; i < totalRequests; i++) {
            long intendedStart = start + i * intervalNanos;
            waitUntil(intendedStart);

            BidRequest request = generator.next();
            AuctionHouse.Plan plan = (auctionHouse == null) ? null : auctionHouse.plan(request);
            Duration deadline = (timeoutOverride != null) ? timeoutOverride : Duration.ofMillis(request.tmax());
            HttpRequest httpRequest = HttpRequest.newBuilder(router.route(request))
                    .timeout(deadline)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(OpenRtbJson.write(request)))
                    .build();

            long actualStart = System.nanoTime();
            client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                    .whenComplete((response, error) -> {
                        long now = System.nanoTime();
                        record(responseTime, now - intendedStart);
                        record(serviceTime, now - actualStart);
                        BidResponse validBid = outcomes.record(request, response, error);
                        if (validBid != null && plan != null) {
                            settleAuction(plan, request, validBid, market);
                        }
                        completed.countDown();
                    });
        }
        double sendSeconds = (System.nanoTime() - start) / 1e9;

        completed.await(30, TimeUnit.SECONDS);
        market.awaitNotices(NOTICE_DRAIN_TIMEOUT);
        return outcomes.toReport(totalRequests, totalRequests / sendSeconds,
                LatencySummary.from(responseTime), LatencySummary.from(serviceTime), capsToAudit,
                (auctionHouse == null) ? null : market.summary());
    }

    private void settleAuction(AuctionHouse.Plan plan, BidRequest request, BidResponse bid, Market market) {
        AuctionHouse.Outcome outcome = AuctionHouse.settle(plan, request, bid);
        market.record(outcome);
        for (String url : outcome.noticeUrls()) {
            HttpRequest notice;
            try {
                notice = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
            } catch (IllegalArgumentException e) {
                market.failures.increment();
                continue;
            }
            market.outstanding.incrementAndGet();
            client.sendAsync(notice, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
                if (error != null || response.statusCode() != 204) {
                    market.failures.increment();
                }
                market.outstanding.decrementAndGet();
            });
        }
    }

    private static void record(Histogram histogram, long elapsedNanos) {
        histogram.recordValue(Math.min(elapsedNanos / 1_000, MAX_LATENCY_MICROS));
    }

    private static void waitUntil(long deadlineNanos) {
        long remaining;
        while ((remaining = deadlineNanos - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    /** Thread-safe auction, notice, and click counters for one run. */
    private static final class Market {
        private final LongAdder won = new LongAdder();
        private final LongAdder lost = new LongAdder();
        private final LongAdder silentLosses = new LongAdder();
        private final LongAdder winNotices = new LongAdder();
        private final LongAdder lossNotices = new LongAdder();
        private final LongAdder duplicates = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder spendMicros = new LongAdder();
        private final LongAdder clicks = new LongAdder();
        private final DoubleAdder expectedClicks = new DoubleAdder();
        private final AtomicInteger outstanding = new AtomicInteger();

        void record(AuctionHouse.Outcome outcome) {
            int firstSends = outcome.noticeUrls().size() - outcome.duplicates();
            if (outcome.won()) {
                won.increment();
                spendMicros.add(Micros.impressionCost(outcome.clearingCpm()));
                winNotices.add(firstSends);
                expectedClicks.add(outcome.trueCtr());
                if (outcome.clicked()) {
                    clicks.increment();
                }
            } else {
                lost.increment();
                if (outcome.noticeUrls().isEmpty()) {
                    silentLosses.increment();
                } else {
                    lossNotices.add(firstSends);
                }
            }
            duplicates.add(outcome.duplicates());
        }

        void awaitNotices(Duration maxWait) throws InterruptedException {
            long deadline = System.nanoTime() + maxWait.toNanos();
            while (outstanding.get() > 0 && System.nanoTime() - deadline < 0) {
                Thread.sleep(10);
            }
        }

        MarketSummary summary() {
            return new MarketSummary(won.sum(), lost.sum(), silentLosses.sum(), winNotices.sum(),
                    lossNotices.sum(), duplicates.sum(), failures.sum(), spendMicros.sum(),
                    clicks.sum(), expectedClicks.sum());
        }
    }

    /** Thread-safe outcome counters plus the frequency audit. LongAdder avoids contention across threads. */
    private static final class Outcomes {
        private final LongAdder bids = new LongAdder();
        private final LongAdder noBids = new LongAdder();
        private final LongAdder invalid = new LongAdder();
        private final LongAdder timeouts = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final FrequencyAudit audit = new FrequencyAudit();

        /** Classifies the response; returns the parsed bid when it is valid, otherwise null. */
        BidResponse record(BidRequest request, HttpResponse<byte[]> response, Throwable error) {
            if (error != null) {
                Throwable cause = (error instanceof CompletionException && error.getCause() != null)
                        ? error.getCause()
                        : error;
                if (cause instanceof HttpTimeoutException) {
                    timeouts.increment();
                } else {
                    errors.increment();
                }
                return null;
            }
            switch (response.statusCode()) {
                case 204 -> {
                    noBids.increment();
                    return null;
                }
                case 200 -> {
                    BidResponse bidResponse = parseValid(request, response.body());
                    if (bidResponse == null) {
                        invalid.increment();
                        return null;
                    }
                    bids.increment();
                    auditExposures(request, bidResponse);
                    return bidResponse;
                }
                default -> {
                    errors.increment();
                    return null;
                }
            }
        }

        /** Parses the response and returns it only if the exchange would accept it; otherwise null. */
        private static BidResponse parseValid(BidRequest request, byte[] body) {
            try {
                BidResponse parsed = OpenRtbJson.readResponse(body);
                return parsed.isValidFor(request) ? parsed : null;
            } catch (UncheckedIOException e) {
                return null;
            }
        }

        private void auditExposures(BidRequest request, BidResponse response) {
            if (request.user() == null || request.user().id() == null) {
                return;
            }
            for (SeatBid seat : response.seatBid()) {
                for (Bid bid : seat.bid()) {
                    if (bid.adId() != null) {
                        audit.record(request.user().id(), bid.adId());
                    }
                }
            }
        }

        LoadReport toReport(long requests, double sendRate, LatencySummary responseTime,
                            LatencySummary serviceTime, Map<String, Integer> capsToAudit, MarketSummary market) {
            return new LoadReport(
                    requests,
                    bids.sum(), noBids.sum(), invalid.sum(), timeouts.sum(), errors.sum(),
                    sendRate, responseTime, serviceTime, audit.summarize(capsToAudit), market);
        }
    }
}
