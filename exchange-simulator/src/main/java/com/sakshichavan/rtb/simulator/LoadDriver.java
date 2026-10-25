package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.OpenRtbJson;
import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop load driver: sends requests on a fixed schedule regardless of how the bidder
 * is responding. Records response time (from the scheduled send, avoiding coordinated omission)
 * and service time (from the actual send) so delays can be attributed to sender or bidder.
 */
public final class LoadDriver implements AutoCloseable {

    private static final long MAX_LATENCY_MICROS = TimeUnit.SECONDS.toMicros(10);

    private final URI target;
    private final HttpClient client;

    public LoadDriver(URI target) {
        this.target = target;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public LoadReport run(TrafficGenerator generator, int qps, Duration duration) throws InterruptedException {
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
            HttpRequest httpRequest = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofMillis(request.tmax()))
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
                LatencySummary.from(responseTime), LatencySummary.from(serviceTime));
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

    /** Thread-safe outcome counters. LongAdder avoids contention when many threads increment at once. */
    private static final class Outcomes {
        private final LongAdder bids = new LongAdder();
        private final LongAdder noBids = new LongAdder();
        private final LongAdder invalid = new LongAdder();
        private final LongAdder timeouts = new LongAdder();
        private final LongAdder errors = new LongAdder();

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
                    if (isValidBid(request, response.body())) {
                        bids.increment();
                    } else {
                        invalid.increment();
                    }
                }
                default -> errors.increment();
            }
        }

        private static boolean isValidBid(BidRequest request, byte[] body) {
            try {
                return OpenRtbJson.readResponse(body).isValidFor(request);
            } catch (UncheckedIOException e) {
                return false;
            }
        }

        LoadReport toReport(long requests, double sendRate, LatencySummary responseTime, LatencySummary serviceTime) {
            return new LoadReport(
                    requests,
                    bids.sum(), noBids.sum(), invalid.sum(), timeouts.sum(), errors.sum(),
                    sendRate, responseTime, serviceTime);
        }
    }
}
