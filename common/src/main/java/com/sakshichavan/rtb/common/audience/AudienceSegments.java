package com.sakshichavan.rtb.common.audience;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * The single definition of each user's interests: the same user id always gets the same segments.
 * Shared so the simulator's ground-truth click model and the bidder's features can never disagree.
 */
public final class AudienceSegments {

    public static final List<String> ALL = List.of(
            "sports-fan", "auto-intender", "traveler", "foodie", "gamer", "finance-pro");
    private static final double[] RATES = {0.25, 0.10, 0.15, 0.20, 0.20, 0.08};

    private AudienceSegments() {
    }

    public static Set<String> forUser(String userId) {
        SplittableRandom random = new SplittableRandom(userId.hashCode());
        Set<String> segments = new HashSet<>();
        for (int i = 0; i < ALL.size(); i++) {
            if (random.nextDouble() < RATES[i]) {
                segments.add(ALL.get(i));
            }
        }
        return Set.copyOf(segments);
    }
}
