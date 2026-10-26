package com.sakshichavan.rtb.bidder.resilience;

import com.sakshichavan.rtb.bidder.AsyncBidEngine;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;
import com.sakshichavan.rtb.common.openrtb.Imp;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdmissionControllerTest {

    private static final BidRequest REQUEST = new BidRequest("r1",
            List.of(new Imp("1", new Banner(300, 250), 0.10, "USD")), null, null, null, 100, null);
    private static final BidResponse BID =
            BidResponse.single("r1", "seat", new Bid("b1", "1", 1.0, "ad", "cr", null));

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void passesRequestsThroughWhenThereIsCapacity() throws Exception {
        AsyncBidEngine instant = request -> CompletableFuture.completedFuture(Optional.of(BID));

        try (AdmissionController admission = new AdmissionController(instant, 2, 10, Duration.ofSeconds(1))) {
            Optional<BidResponse> result = admission.evaluate(REQUEST).toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertTrue(result.isPresent());
            assertEquals(1, admission.stats().admitted());
        }
    }

    @Test
    void shedsInstantlyWhenTheQueueIsFull() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AsyncBidEngine blocking = request -> {
            awaitQuietly(release);
            return CompletableFuture.completedFuture(Optional.of(BID));
        };

        try (AdmissionController admission = new AdmissionController(blocking, 1, 1, Duration.ofSeconds(10))) {
            CompletableFuture<Optional<BidResponse>> running = admission.evaluate(REQUEST).toCompletableFuture();
            CompletableFuture<Optional<BidResponse>> queued = admission.evaluate(REQUEST).toCompletableFuture();
            CompletableFuture<Optional<BidResponse>> rejected = admission.evaluate(REQUEST).toCompletableFuture();

            assertTrue(rejected.isDone(), "a full queue must answer immediately");
            assertTrue(rejected.get().isEmpty());
            assertEquals(1, admission.stats().shedQueueFull());

            release.countDown();
            assertTrue(running.get(1, TimeUnit.SECONDS).isPresent());
            assertTrue(queued.get(1, TimeUnit.SECONDS).isPresent());
        }
    }

    @Test
    void dropsRequestsThatWaitedTooLongWithoutDoingTheWork() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AsyncBidEngine slow = request -> {
            calls.incrementAndGet();
            sleepQuietly(100);
            return CompletableFuture.completedFuture(Optional.of(BID));
        };

        try (AdmissionController admission = new AdmissionController(slow, 1, 10, Duration.ofMillis(20))) {
            CompletableFuture<Optional<BidResponse>> first = admission.evaluate(REQUEST).toCompletableFuture();
            CompletableFuture<Optional<BidResponse>> second = admission.evaluate(REQUEST).toCompletableFuture();

            assertTrue(first.get(1, TimeUnit.SECONDS).isPresent());
            assertTrue(second.get(1, TimeUnit.SECONDS).isEmpty());
            assertEquals(1, calls.get(), "the stale request must not reach the engine");
            assertEquals(1, admission.stats().shedStale());
        }
    }
}
