package com.sakshichavan.rtb.bidder.chaos;

import com.sakshichavan.rtb.bidder.budget.SpendGuard;
import com.sakshichavan.rtb.bidder.chaos.BidderHarness.Burst;
import com.sakshichavan.rtb.bidder.profile.AerospikeProfileStore;
import com.sakshichavan.rtb.bidder.profile.UserProfile;
import com.sakshichavan.rtb.bidder.resilience.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Freezes Aerospike mid-traffic (a hung database, the hardest failure for callers) and checks degradation and recovery. */
@Testcontainers
class AerospikeHangChaosTest {

    @Container
    static final GenericContainer<?> AEROSPIKE = new GenericContainer<>(DockerImageName.parse("aerospike:ce-8.1.2.4"))
            .withExposedPorts(3000)
            .waitingFor(Wait.forLogMessage(".*service ready: soon there will be cake!.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    private static void freeze(boolean frozen) {
        String id = AEROSPIKE.getContainerId();
        if (frozen) {
            AEROSPIKE.getDockerClient().pauseContainerCmd(id).exec();
        } else {
            AEROSPIKE.getDockerClient().unpauseContainerCmd(id).exec();
        }
    }

    @Test
    void keepsBiddingWhileAerospikeIsFrozenThenRecovers() throws Exception {
        try (AerospikeProfileStore store = AerospikeProfileStore.connectWithRetry(
                AEROSPIKE.getHost(), AEROSPIKE.getMappedPort(3000), "test", 7, Duration.ofSeconds(30))) {
            for (int i = 0; i < 50; i++) {
                store.put("user-" + i, new UserProfile(Set.of("traveler")));
            }
            CircuitBreaker breaker =
                    new CircuitBreaker(0.5, 10, Duration.ofSeconds(1), Duration.ofMillis(500), System::nanoTime);

            try (BidderHarness bidder = new BidderHarness(store, SpendGuard.UNLIMITED, breaker)) {
                Burst healthy = bidder.send(100);
                assertEquals(100, healthy.bids(), healthy.toString());
                assertTrue(healthy.audienceBids() > 0, "profiles should flow while healthy: " + healthy);

                freeze(true);
                Burst frozen;
                try {
                    frozen = bidder.send(300);
                } finally {
                    freeze(false);
                }

                assertEquals(0, frozen.failures(), "every request must still be answered: " + frozen);
                assertEquals(300, frozen.bids(), "the broad campaign keeps bidding: " + frozen);
                assertEquals(0, frozen.audienceBids(), "no profiles can arrive while frozen: " + frozen);
                assertTrue(frozen.maxLatencyMillis() < 100, "latency must stay bounded: " + frozen);
                assertTrue(bidder.pipeline.stats().breakerOpens() > 0, "breaker should have opened");
                assertTrue(bidder.pipeline.stats().shortCircuited() > 0, "lookups should be skipped while open");

                assertTrue(recovers(bidder), "breaker should close and audience bids resume after unfreezing");
            }
        }
    }

    private static boolean recovers(BidderHarness bidder) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() - deadline < 0) {
            Burst burst = bidder.send(20);
            if ("CLOSED".equals(bidder.pipeline.stats().breakerState()) && burst.audienceBids() > 0) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }
}
