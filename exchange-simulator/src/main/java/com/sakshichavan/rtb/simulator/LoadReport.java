package com.sakshichavan.rtb.simulator;

import java.util.List;

/**
 * Results of one load test run.
 * responseTime: measured from each request's scheduled send time (what an exchange experiences;
 *               includes any lag in the sender, so there is no coordinated omission).
 * serviceTime:  measured from the actual send time (network plus bidder only).
 * exposures:    per-user frequency ground truth for the campaigns that were audited.
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
        LatencySummary serviceTime,
        List<CampaignExposure> exposures) {

    public LoadReport {
        exposures = List.copyOf(exposures);
    }

    public long completed() {
        return bids + noBids + invalid + timeouts + errors;
    }

    public String summary() {
        StringBuilder text = new StringBuilder("""
                requests     %d sent, %d completed, at %.0f req/s
                outcomes     bids %d | no-bids %d | invalid %d | timeouts %d | errors %d
                response ms  %s   (from scheduled send)
                service ms   %s   (from actual send)"""
                .formatted(
                        requests, completed(), sendRate,
                        bids, noBids, invalid, timeouts, errors,
                        responseTime.format(),
                        serviceTime.format()));
        for (CampaignExposure exposure : exposures) {
            text.append("\nfrequency    ").append(exposure.format());
        }
        return text.toString();
    }
}
