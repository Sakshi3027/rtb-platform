package com.sakshichavan.rtb.bidder.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticProfilesTest {

    @Test
    void sameUserAlwaysGetsTheSameSegments() {
        assertEquals(SyntheticProfiles.forUser("user-7"), SyntheticProfiles.forUser("user-7"));
    }

    @Test
    void segmentRatesMatchTheirConfiguredShares() {
        int sportsFans = 0;
        int users = 20_000;
        for (int i = 0; i < users; i++) {
            if (SyntheticProfiles.forUser("user-" + i).segments().contains("sports-fan")) {
                sportsFans++;
            }
        }
        double share = sportsFans / (double) users;
        assertTrue(share > 0.22 && share < 0.28, "sports-fan share was " + share);
    }
}
