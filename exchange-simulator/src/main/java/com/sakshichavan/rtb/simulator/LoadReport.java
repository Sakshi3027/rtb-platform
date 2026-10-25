package com.sakshichavan.rtb.simulator;

/**
 * Results of one load test run. Latencies are measured from each request's
 * intended send time, so stalls in the bidder show up in the tail (no coordinated omission).
 */
public record LoadReport(
        long requests,
        long bids,
        long noBids,
        long invalid,
        long timeouts,
        long errors,
        double sendRate,
        long p50Micros,
        long p90Micros,
        long p99Micros,
        long p999Micros,
        long maxMicros) {

    public long completed() {
        return bids + noBids + invalid + timeouts + errors;
    }

    public String summary() {
        return """
                requests   %d sent, %d completed, at %.0f req/s
                outcomes   bids %d | no-bids %d | invalid %d | timeouts %d | errors %d
                latency ms p50 %.2f | p90 %.2f | p99 %.2f | p99.9 %.2f | max %.2f"""
                .formatted(
                        requests, completed(), sendRate,
                        bids, noBids, invalid, timeouts, errors,
                        p50Micros / 1000.0, p90Micros / 1000.0, p99Micros / 1000.0,
                        p999Micros / 1000.0, maxMicros / 1000.0);
    }
}
