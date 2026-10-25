package com.sakshichavan.rtb.simulator;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadDriverTest {

    private static final WeightedSampler<Integer> USERS = TrafficGenerator.userSampler(10_000);

    private static LoadReport runAgainst(Duration bidderDelay, double bidRate, int qps) throws Exception {
        try (StubBidder bidder = new StubBidder(0, bidderDelay, bidRate);
             LoadDriver driver = new LoadDriver(bidder.bidUri())) {
            return driver.run(new TrafficGenerator(42, 0, USERS), qps, Duration.ofSeconds(1));
        }
    }

    @Test
    void countsValidBidsWhenBidderAlwaysBids() throws Exception {
        LoadReport report = runAgainst(Duration.ZERO, 1.0, 200);

        assertEquals(200, report.completed());
        assertEquals(200, report.bids());
        assertEquals(0, report.invalid());
    }

    @Test
    void countsNoBidsWhenBidderNeverBids() throws Exception {
        LoadReport report = runAgainst(Duration.ZERO, 0.0, 200);

        assertEquals(200, report.noBids());
        assertEquals(0, report.bids());
    }

    @Test
    void countsTimeoutsWhenBidderIsSlowerThanTmax() throws Exception {
        LoadReport report = runAgainst(Duration.ofMillis(300), 1.0, 50);

        assertEquals(50, report.timeouts());
        assertEquals(0, report.bids());
    }

    @Test
    void latencyReflectsBidderDelay() throws Exception {
        LoadReport report = runAgainst(Duration.ofMillis(20), 1.0, 100);

        assertTrue(report.responseTime().p50Micros() >= 20_000,
                "response p50 was " + report.responseTime().p50Micros() + " micros");
        assertTrue(report.serviceTime().p50Micros() >= 20_000,
                "service p50 was " + report.serviceTime().p50Micros() + " micros");
    }

    @Test
    void responseTimeIsNeverBelowServiceTime() throws Exception {
        LoadReport report = runAgainst(Duration.ofMillis(5), 1.0, 200);

        assertTrue(report.responseTime().p50Micros() >= report.serviceTime().p50Micros());
        assertTrue(report.responseTime().maxMicros() >= report.serviceTime().maxMicros());
    }
}
