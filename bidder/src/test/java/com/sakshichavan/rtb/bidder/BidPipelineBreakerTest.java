package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.budget.SpendGuard;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.bidder.frequency.FrequencyGuard;
import com.sakshichavan.rtb.bidder.profile.ProfileStore;
import com.sakshichavan.rtb.bidder.profile.UserProfile;
import com.sakshichavan.rtb.bidder.resilience.CircuitBreaker;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Imp;
import com.sakshichavan.rtb.common.openrtb.User;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BidPipelineBreakerTest {

    private static BidRequest request(int i) {
        Imp imp = new Imp("1", new Banner(300, 250), 0.10, "USD");
        return new BidRequest("r-" + i, List.of(imp), null, null, new User("u-" + i), 100, null);
    }

    @Test
    void stopsCallingABrokenStoreButKeepsBidding() throws Exception {
        AtomicInteger storeCalls = new AtomicInteger();
        ProfileStore broken = userId -> {
            storeCalls.incrementAndGet();
            return CompletableFuture.<UserProfile>failedFuture(new IllegalStateException("aerospike is down"));
        };
        AtomicLong now = new AtomicLong(0);
        CircuitBreaker breaker = new CircuitBreaker(0.5, 5, Duration.ofSeconds(1), Duration.ofSeconds(2), now::get);
        TargetingBidEngine engine = new TargetingBidEngine(
                new TargetingIndex(List.of(new Campaign("broad", "cr-1", 1.0, 100.0, null))),
                new BidPricer(0.5), SpendGuard.UNLIMITED, FrequencyGuard.UNLIMITED);

        try (BidPipeline pipeline = new BidPipeline(broken, engine, 20, breaker)) {
            for (int i = 0; i < 15; i++) {
                assertTrue(pipeline.evaluate(request(i)).toCompletableFuture().get(1, TimeUnit.SECONDS).isPresent());
            }
            assertEquals(5, storeCalls.get());
            assertEquals(10, pipeline.stats().shortCircuited());
            assertEquals("OPEN", pipeline.stats().breakerState());

            now.addAndGet(TimeUnit.SECONDS.toNanos(3));
            pipeline.evaluate(request(99)).toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertEquals(6, storeCalls.get());
            assertEquals("OPEN", pipeline.stats().breakerState());
        }
    }
}
