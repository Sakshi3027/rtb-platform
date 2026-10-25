package com.sakshichavan.rtb.simulator;

/**
 * Results of one load test run.
 * responseTime: measured from each request's scheduled send time (what an exchange experiences;
 *               includes any lag in the sender, so there is no coordinated omission).
 * serviceTime:  measured from the actual send time (network plus bidder only).
 * A large gap between the two tails means the sender fell behind schedule, not the bidder.
 */
public record LoadReport(
        long requests,
        long bids,
        long noBids,
        long invalid,
        long timeouts,
        long errors,
        double sendRate,
        LatencySummary responseTime,
        LatencySummary serviceTime) {

    public long completed() {
        return bids + noBids + invalid + timeouts + errors;
    }

    public String summary() {
        return """
                requests     %d sent, %d completed, at %.0f req/s
                outcomes     bids %d | no-bids %d | invalid %d | timeouts %d | errors %d
                response ms  %s   (from scheduled send)
                service ms   %s   (from actual send)"""
                .formatted(
                        requests, completed(), sendRate,
                        bids, noBids, invalid, timeouts, errors,
                        responseTime.format(),
                        serviceTime.format());
    }
}
