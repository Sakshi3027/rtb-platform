package com.sakshichavan.rtb.simulator;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadDriverTest {

    private static final WeightedSampler<Integer> USERS = TrafficGenerator.userSampler(10_000);

    /** Generous deadline for tests that check classification, not timing; keeps them stable on a busy machine. */
    private static final Duration RELAXED_DEADLINE = Duration.ofSeconds(5);

    private static LoadReport runAgainst(Duration bidderDelay, double bidRate, int qps, Duration timeoutOverride)
            throws Exception {
        try (StubBidder bidder = new StubBidder(0, bidderDelay, bidRate);
             LoadDriver driver = new LoadDriver(bidder.bidUri(), timeoutOverride)) {
            return driver.run(new TrafficGenerator(42, 0, USERS), qps, Duration.ofSeconds(1));
        }
    }

    @Test
    void countsValidBidsWhenBidderAlwaysBids() throws Exception {
        LoadReport report = runAgainst(Duration.ZERO, 1.0, 200, RELAXED_DEADLINE);

        assertEquals(200, report.completed(), report.summary());
        assertEquals(200, report.bids(), report.summary());
        assertEquals(0, report.invalid(), report.summary());
    }

    @Test
    void countsNoBidsWhenBidderNeverBids() throws Exception {
        LoadReport report = runAgainst(Duration.ZERO, 0.0, 200, RELAXED_DEADLINE);

        assertEquals(200, report.noBids(), report.summary());
        assertEquals(0, report.bids(), report.summary());
    }

    @Test
    void enforcesTmaxWhenBidderIsSlowerThanTheDeadline() throws Exception {
        LoadReport report = runAgainst(Duration.ofMillis(300), 1.0, 50, null);

        assertEquals(50, report.timeouts(), report.summary());
        assertEquals(0, report.bids(), report.summary());
    }

    @Test
    void latencyReflectsBidderDelay() throws Exception {
        LoadReport report = runAgainst(Duration.ofMillis(20), 1.0, 100, RELAXED_DEADLINE);

        assertTrue(report.responseTime().p50Micros() >= 20_000, report.summary());
        assertTrue(report.serviceTime().p50Micros() >= 20_000, report.summary());
    }

    @Test
    void responseTimeIsNeverBelowServiceTime() throws Exception {
        LoadReport report = runAgainst(Duration.ofMillis(5), 1.0, 200, RELAXED_DEADLINE);

        assertTrue(report.responseTime().p50Micros() >= report.serviceTime().p50Micros(), report.summary());
        assertTrue(report.responseTime().maxMicros() >= report.serviceTime().maxMicros(), report.summary());
    }
}
