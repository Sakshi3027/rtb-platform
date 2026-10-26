package com.sakshichavan.rtb.bidder.budget;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

    private static final Instant SIX_AM = Instant.parse("2026-10-25T06:00:00Z");
    private static final Instant NOON = Instant.parse("2026-10-25T12:00:00Z");

    protected BudgetSource source;

    protected abstract BudgetSource newSource(Clock clock, PacingPolicy pacing);

    @BeforeEach
    void createSource() {
        source = newSource(Clock.systemUTC(), PacingPolicy.NONE);
    }

    @AfterEach
    void closeSource() {
        source.close();
    }

    /** Unique id per test, so tests sharing one Redis never see each other's keys. */
    protected static String freshCampaign() {
        return "c-" + UUID.randomUUID();
    }

    private static long lease(BudgetSource budgetSource, String campaignId, long micros) {
        return budgetSource.lease(campaignId, micros).toCompletableFuture().join();
    }

    private static boolean initialize(BudgetSource budgetSource, String campaignId, long micros) {
        return budgetSource.initialize(campaignId, micros).toCompletableFuture().join();
    }

    @Test
    void leasesTheRequestedAmountWhileBudgetRemains() {
        String campaign = freshCampaign();
        initialize(source, campaign, 10_000);

        assertEquals(3_000, lease(source, campaign, 3_000));
        assertEquals(3_000, lease(source, campaign, 3_000));
    }

    @Test
    void grantsWhatIsLeftThenNothing() {
        String campaign = freshCampaign();
        initialize(source, campaign, 5_000);

        assertEquals(3_000, lease(source, campaign, 3_000));
        assertEquals(2_000, lease(source, campaign, 3_000));
        assertEquals(0, lease(source, campaign, 3_000));
    }

    @Test
    void unknownCampaignGetsNothing() {
        assertEquals(0, lease(source, freshCampaign(), 1_000));
    }

    @Test
    void initializeNeverOverwritesAnExistingBudget() {
        String campaign = freshCampaign();

        assertTrue(initialize(source, campaign, 10_000));
        assertFalse(initialize(source, campaign, 99_999));
        assertEquals(10_000, lease(source, campaign, 50_000));
    }

    @Test
    void neverGrantsMoreThanTheBudgetUnderConcurrency() throws Exception {
        String campaign = freshCampaign();
        initialize(source, campaign, 1_000_000);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        long totalGranted = 0;
        try {
            List<Future<Long>> leases = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                leases.add(pool.submit(() -> lease(source, campaign, 7_000)));
            }
            for (Future<Long> granted : leases) {
                totalGranted += granted.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1_000_000, totalGranted);
    }

    @Test
    void evenPacingCapsSpendAtTheElapsedShareOfTheDay() {
        String campaign = freshCampaign();
        try (BudgetSource paced = newSource(Clock.fixed(NOON, ZoneOffset.UTC), PacingPolicy.even(0.0))) {
            initialize(paced, campaign, 1_000_000);

            assertEquals(500_000, lease(paced, campaign, 1_000_000));
            assertEquals(0, lease(paced, campaign, 1_000));
        }
    }

    @Test
    void burstLetsSpendRunSlightlyAheadOfSchedule() {
        String campaign = freshCampaign();
        try (BudgetSource paced = newSource(Clock.fixed(NOON, ZoneOffset.UTC), PacingPolicy.even(0.10))) {
            initialize(paced, campaign, 1_000_000);

            assertEquals(600_000, lease(paced, campaign, 1_000_000));
        }
    }

    @Test
    void allowanceGrowsAsTheDayProgresses() {
        String campaign = freshCampaign();
        MutableClock clock = new MutableClock(SIX_AM);
        try (BudgetSource paced = newSource(clock, PacingPolicy.even(0.0))) {
            initialize(paced, campaign, 1_000_000);
            assertEquals(250_000, lease(paced, campaign, 1_000_000));
            assertEquals(0, lease(paced, campaign, 1_000));

            clock.advance(Duration.ofHours(6));

            assertEquals(250_000, lease(paced, campaign, 1_000_000));
        }
    }

    /** A clock tests can move forward, so pacing over hours is verified instantly. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
