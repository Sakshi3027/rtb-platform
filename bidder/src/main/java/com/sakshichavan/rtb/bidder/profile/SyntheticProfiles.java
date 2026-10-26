package com.sakshichavan.rtb.bidder.profile;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;

/**
 * Deterministic fake audience data: the same user id always gets the same segments.
 * Used when no real profile database is configured, and to seed one for testing.
 */
public final class SyntheticProfiles {

    private static final String[] SEGMENTS = {
            "sports-fan", "auto-intender", "traveler", "foodie", "gamer", "finance-pro"};
    private static final double[] RATES = {0.25, 0.10, 0.15, 0.20, 0.20, 0.08};

    private SyntheticProfiles() {
    }

    public static UserProfile forUser(String userId) {
        SplittableRandom random = new SplittableRandom(userId.hashCode());
        Set<String> segments = new HashSet<>();
        for (int i = 0; i < SEGMENTS.length; i++) {
            if (random.nextDouble() < RATES[i]) {
                segments.add(SEGMENTS[i]);
            }
        }
        return new UserProfile(segments);
    }

    /** An in-process store that answers instantly from the synthetic data. */
    public static ProfileStore store() {
        return userId -> CompletableFuture.completedFuture(forUser(userId));
    }
}
