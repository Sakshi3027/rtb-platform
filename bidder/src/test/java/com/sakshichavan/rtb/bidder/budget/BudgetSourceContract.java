package com.sakshichavan.rtb.bidder.budget;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior every BudgetSource must have. Each implementation's test class extends this,
 * proving the in-memory and Redis versions are interchangeable.
 */
abstract class BudgetSourceContract {

    protected BudgetSource source;

    protected abstract BudgetSource newSource();

    @BeforeEach
    void createSource() {
        source = newSource();
    }

    @AfterEach
    void closeSource() {
        source.close();
    }

    /** Unique id per test, so tests sharing one Redis never see each other's keys. */
    protected static String freshCampaign() {
        return "c-" + UUID.randomUUID();
    }

    private long lease(String campaignId, long micros) {
        return source.lease(campaignId, micros).toCompletableFuture().join();
    }

    private boolean initialize(String campaignId, long micros) {
        return source.initialize(campaignId, micros).toCompletableFuture().join();
    }

    @Test
    void leasesTheRequestedAmountWhileBudgetRemains() {
        String campaign = freshCampaign();
        initialize(campaign, 10_000);

        assertEquals(3_000, lease(campaign, 3_000));
        assertEquals(3_000, lease(campaign, 3_000));
    }

    @Test
    void grantsWhatIsLeftThenNothing() {
        String campaign = freshCampaign();
        initialize(campaign, 5_000);

        assertEquals(3_000, lease(campaign, 3_000));
        assertEquals(2_000, lease(campaign, 3_000));
        assertEquals(0, lease(campaign, 3_000));
    }

    @Test
    void unknownCampaignGetsNothing() {
        assertEquals(0, lease(freshCampaign(), 1_000));
    }

    @Test
    void initializeNeverOverwritesAnExistingBudget() {
        String campaign = freshCampaign();

        assertTrue(initialize(campaign, 10_000));
        assertFalse(initialize(campaign, 99_999));
        assertEquals(10_000, lease(campaign, 50_000));
    }

    @Test
    void neverGrantsMoreThanTheBudgetUnderConcurrency() throws Exception {
        String campaign = freshCampaign();
        initialize(campaign, 1_000_000);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        long totalGranted = 0;
        try {
            List<Future<Long>> leases = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                leases.add(pool.submit(() -> lease(campaign, 7_000)));
            }
            for (Future<Long> granted : leases) {
                totalGranted += granted.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1_000_000, totalGranted);
    }
}
