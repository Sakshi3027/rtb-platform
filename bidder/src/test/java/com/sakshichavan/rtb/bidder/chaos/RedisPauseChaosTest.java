package com.sakshichavan.rtb.bidder.chaos;

import com.sakshichavan.rtb.bidder.budget.CampaignBudgets;
import com.sakshichavan.rtb.bidder.budget.RedisBudgetSource;
import com.sakshichavan.rtb.bidder.chaos.BidderHarness.Burst;
import com.sakshichavan.rtb.bidder.profile.ProfileStore;
import com.sakshichavan.rtb.bidder.profile.UserProfile;
import com.sakshichavan.rtb.bidder.resilience.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Freezes Redis mid-traffic. Budget leasing means bids keep flowing from the local lease with no Redis calls,
 * stop cleanly once the lease is spent (never overspending), and resume once refills succeed again.
 */
@Testcontainers
class RedisPauseChaosTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static void freeze(boolean frozen) {
        String id = REDIS.getContainerId();
        if (frozen) {
            REDIS.getDockerClient().pauseContainerCmd(id).exec();
        } else {
            REDIS.getDockerClient().unpauseContainerCmd(id).exec();
        }
    }

    @Test
    void keepsBiddingFromLocalLeasesWhileRedisIsFrozen() throws Exception {
        String uri = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        try (RedisBudgetSource source = new RedisBudgetSource(uri, Clock.systemUTC())) {
            CampaignBudgets budgets = new CampaignBudgets(source, 100_000, 20_000, System::nanoTime);
            budgets.initialize(BidderHarness.CAMPAIGNS).toCompletableFuture().join();
            ProfileStore noProfiles = userId -> CompletableFuture.completedFuture(UserProfile.UNKNOWN);

            try (BidderHarness bidder = new BidderHarness(noProfiles, budgets, CircuitBreaker.withDefaults(System::nanoTime))) {
                Burst healthy = bidder.send(50);
                assertEquals(50, healthy.bids(), healthy.toString());

                freeze(true);
                Burst frozen;
                try {
                    frozen = bidder.send(300);
                } finally {
                    freeze(false);
                }

                assertEquals(0, frozen.failures(), "every request must still be answered: " + frozen);
                assertEquals(300, frozen.answered(), frozen.toString());
                assertTrue(frozen.bids() > 0, "bids should continue from the local lease: " + frozen);
                assertTrue(frozen.bids() < 300, "bidding must stop once the lease is spent, never overspend: " + frozen);
                assertTrue(frozen.maxLatencyMillis() < 100, "latency must stay bounded: " + frozen);

                assertTrue(resumesBidding(bidder), "refills should succeed and bids resume after unfreezing");
            }
        }
    }

    private static boolean resumesBidding(BidderHarness bidder) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() - deadline < 0) {
            if (bidder.send(20).bids() > 0) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }
}
