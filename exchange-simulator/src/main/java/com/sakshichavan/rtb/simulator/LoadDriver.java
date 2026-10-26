package com.sakshichavan.rtb.simulator;

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
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop load driver: sends requests on a fixed schedule regardless of how the bidders respond.
 * A Router picks the bidder for each request, like the load balancer in front of a fleet.
 * Records response time (from the scheduled send, avoiding coordinated omission), service time
 * (from the actual send), and per-user frequency ground truth for audited campaigns.
 */
public final class LoadDriver implements AutoCloseable {

    private static final long MAX_LATENCY_MICROS = TimeUnit.SECONDS.toMicros(10);

    private final Router router;
    private final Duration timeoutOverride;
    private final HttpClient client;

    /** Single bidder; enforces each request's tmax as its deadline, like a real exchange. */
    public LoadDriver(URI target) {
        this(Router.single(target), null);
    }

    public LoadDriver(URI target, Duration timeoutOverride) {
        this(Router.single(target), timeoutOverride);
    }

    /**
     * @param timeoutOverride if non-null, used instead of each request's tmax. Useful for tests that
     *                        check behavior unrelated to deadlines, and for benchmarking without cutoffs.
     */
    public LoadDriver(Router router, Duration timeoutOverride) {
        this.router = router;
        this.timeoutOverride = timeoutOverride;
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
        CountDownLatch completed = new CountDownLatch((int) totalRequests);

        long start = System.nanoTime();
        for (long i = 0; i < totalRequests; i++) {
            long intendedStart = start + i * intervalNanos;
            waitUntil(intendedStart);

            BidRequest request = generator.next();
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
                        outcomes.record(request, response, error);
                        completed.countDown();
                    });
        }
        double sendSeconds = (System.nanoTime() - start) / 1e9;

        completed.await(30, TimeUnit.SECONDS);
        return outcomes.toReport(totalRequests, totalRequests / sendSeconds,
                LatencySummary.from(responseTime), LatencySummary.from(serviceTime), capsToAudit);
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

    /** Thread-safe outcome counters plus the frequency audit. LongAdder avoids contention across threads. */
    private static final class Outcomes {
        private final LongAdder bids = new LongAdder();
        private final LongAdder noBids = new LongAdder();
        private final LongAdder invalid = new LongAdder();
        private final LongAdder timeouts = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final FrequencyAudit audit = new FrequencyAudit();

        void record(BidRequest request, HttpResponse<byte[]> response, Throwable error) {
            if (error != null) {
                Throwable cause = (error instanceof CompletionException && error.getCause() != null)
                        ? error.getCause()
                        : error;
                if (cause instanceof HttpTimeoutException) {
                    timeouts.increment();
                } else {
                    errors.increment();
                }
                return;
            }
            switch (response.statusCode()) {
                case 204 -> noBids.increment();
                case 200 -> {
                    BidResponse bidResponse = parseValid(request, response.body());
                    if (bidResponse == null) {
                        invalid.increment();
                    } else {
                        bids.increment();
                        auditExposures(request, bidResponse);
                    }
                }
                default -> errors.increment();
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
                            LatencySummary serviceTime, Map<String, Integer> capsToAudit) {
            return new LoadReport(
                    requests,
                    bids.sum(), noBids.sum(), invalid.sum(), timeouts.sum(), errors.sum(),
                    sendRate, responseTime, serviceTime, audit.summarize(capsToAudit));
        }
    }
}
