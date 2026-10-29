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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kills Aerospike mid-traffic and checks the bidder keeps answering every request without it. */
@Testcontainers
class AerospikeOutageChaosTest {

    @Container
    static final GenericContainer<?> AEROSPIKE = new GenericContainer<>(DockerImageName.parse("aerospike:ce-8.1.2.4"))
            .withExposedPorts(3000)
            .waitingFor(Wait.forLogMessage(".*service ready: soon there will be cake!.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    @Test
    void keepsBiddingAfterAerospikeDies() throws Exception {
        try (AerospikeProfileStore store = AerospikeProfileStore.connectWithRetry(
                AEROSPIKE.getHost(), AEROSPIKE.getMappedPort(3000), "test", 7, Duration.ofSeconds(30))) {
            for (int i = 0; i < 50; i++) {
                store.put("user-" + i, new UserProfile(Set.of("traveler")));
            }
            CircuitBreaker breaker =
                    new CircuitBreaker(0.5, 10, Duration.ofSeconds(1), Duration.ofMillis(500), System::nanoTime);

            try (BidderHarness bidder = new BidderHarness(store, SpendGuard.UNLIMITED, breaker)) {
                Burst healthy = bidder.send(100);
                assertTrue(healthy.audienceBids() > 0, "profiles should flow while healthy: " + healthy);

                AEROSPIKE.getDockerClient().stopContainerCmd(AEROSPIKE.getContainerId()).exec();
                Burst dead = bidder.send(300);

                assertEquals(0, dead.failures(), "every request must still be answered: " + dead);
                assertEquals(300, dead.bids(), "the broad campaign keeps bidding: " + dead);
                assertEquals(0, dead.audienceBids(), "no profiles can arrive from a dead database: " + dead);
                assertTrue(dead.maxLatencyMillis() < 100, "latency must stay bounded: " + dead);
                assertTrue(bidder.pipeline.stats().breakerOpens() > 0, "breaker should have opened");
            }
        }
    }
}
