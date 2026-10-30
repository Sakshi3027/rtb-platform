package com.sakshichavan.rtb.common.audience;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudienceSegmentsTest {

    @Test
    void theSameUserAlwaysGetsTheSameSegments() {
        assertEquals(AudienceSegments.forUser("user-42"), AudienceSegments.forUser("user-42"));
    }

    @Test
    void matchesTheOriginalSyntheticProfilesAlgorithm() {
        double[] originalRates = {0.25, 0.10, 0.15, 0.20, 0.20, 0.08};
        for (int user = 0; user < 1_000; user++) {
            String userId = "user-" + user;
            SplittableRandom random = new SplittableRandom(userId.hashCode());
            Set<String> expected = new HashSet<>();
            for (int i = 0; i < AudienceSegments.ALL.size(); i++) {
                if (random.nextDouble() < originalRates[i]) {
                    expected.add(AudienceSegments.ALL.get(i));
                }
            }
            assertEquals(expected, AudienceSegments.forUser(userId), "segments changed for " + userId);
        }
    }

    @Test
    void segmentSharesMatchTheConfiguredRates() {
        int users = 100_000;
        long sportsFans = IntStream.range(0, users)
                .filter(i -> AudienceSegments.forUser("user-" + i).contains("sports-fan"))
                .count();
        double share = (double) sportsFans / users;

        assertTrue(share > 0.24 && share < 0.26, "sports-fan share was " + share);
    }
}
