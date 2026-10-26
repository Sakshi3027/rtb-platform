package com.sakshichavan.rtb.bidder.budget;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * How much of a campaign's daily budget may have been spent by a given moment.
 * Enforced inside the atomic lease, so it applies globally across all bidder instances.
 */
@FunctionalInterface
public interface PacingPolicy {

    double SECONDS_PER_DAY = 86_400.0;

    /** Fraction of the daily budget, between 0 and 1, that may be spent by this instant. */
    double allowedFraction(Instant now);

    /** No pacing: the whole budget is available immediately. */
    PacingPolicy NONE = now -> 1.0;

    /**
     * Even pacing: spend tracks the elapsed share of the UTC day, plus a small burst so bidding
     * can start right after midnight and absorb short traffic spikes.
     */
    static PacingPolicy even(double burstFraction) {
        if (burstFraction < 0 || burstFraction > 1) {
            throw new IllegalArgumentException("burst fraction must be in [0, 1]: " + burstFraction);
        }
        return now -> Math.min(1.0, dayElapsedFraction(now) + burstFraction);
    }

    private static double dayElapsedFraction(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalTime().toSecondOfDay() / SECONDS_PER_DAY;
    }
}
