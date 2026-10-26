package com.sakshichavan.rtb.bidder.frequency;

import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.FrequencyCap;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrequencyCapperTest {

    private static final long ONE_HOUR_NANOS = TimeUnit.HOURS.toNanos(1);

    private final AtomicLong now = new AtomicLong(0);
    private final FrequencyCapper capper = new FrequencyCapper(10_000, Duration.ofHours(1), now::get);

    private static Campaign capped(String id, int maxImpressions) {
        return new Campaign(id, "cr-" + id, 2.0, 100.0, null, new FrequencyCap(maxImpressions, 3_600));
    }

    private static Campaign uncapped(String id) {
        return new Campaign(id, "cr-" + id, 2.0, 100.0, null);
    }

    @Test
    void allowsUpToTheCapThenBlocks() {
        Campaign campaign = capped("c1", 3);

        assertTrue(capper.tryAcquire("u1", campaign));
        assertTrue(capper.tryAcquire("u1", campaign));
        assertTrue(capper.tryAcquire("u1", campaign));
        assertFalse(capper.tryAcquire("u1", campaign));
    }

    @Test
    void capsAreIndependentPerUserAndPerCampaign() {
        Campaign first = capped("c1", 1);
        Campaign second = capped("c2", 1);

        assertTrue(capper.tryAcquire("u1", first));
        assertFalse(capper.tryAcquire("u1", first));
        assertTrue(capper.tryAcquire("u2", first));
        assertTrue(capper.tryAcquire("u1", second));
    }

    @Test
    void windowSlidesSoOldImpressionsExpire() {
        Campaign campaign = capped("c1", 2);
        assertTrue(capper.tryAcquire("u1", campaign));
        now.addAndGet(TimeUnit.MINUTES.toNanos(30));
        assertTrue(capper.tryAcquire("u1", campaign));
        assertFalse(capper.tryAcquire("u1", campaign));

        now.set(ONE_HOUR_NANOS);

        assertTrue(capper.tryAcquire("u1", campaign));
        assertFalse(capper.tryAcquire("u1", campaign));
    }

    @Test
    void uncappedCampaignsAreAlwaysAllowed() {
        Campaign campaign = uncapped("c1");

        for (int i = 0; i < 100; i++) {
            assertTrue(capper.tryAcquire("u1", campaign));
        }
    }

    @Test
    void anonymousUsersAreBlockedOnlyByCappedCampaigns() {
        assertFalse(capper.tryAcquire(null, capped("c1", 3)));
        assertTrue(capper.tryAcquire(null, uncapped("c2")));
    }

    @Test
    void releaseGivesTheSlotBack() {
        Campaign campaign = capped("c1", 1);
        assertTrue(capper.tryAcquire("u1", campaign));

        capper.release("u1", campaign);

        assertTrue(capper.tryAcquire("u1", campaign));
    }

    @Test
    void neverExceedsTheCapUnderConcurrency() throws Exception {
        Campaign campaign = capped("c1", 3);
        LongAdder granted = new LongAdder();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                workers.add(pool.submit(() -> {
                    startGate.await();
                    for (int i = 0; i < 100; i++) {
                        if (capper.tryAcquire("hot-user", campaign)) {
                            granted.increment();
                        }
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> worker : workers) {
                worker.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(3, granted.sum());
    }
}
