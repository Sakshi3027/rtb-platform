package com.sakshichavan.rtb.bidder.campaign;

import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Imp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TargetingIndexSegmentsTest {

    private static final Campaign AUTO_AUDIENCE = new Campaign("auto", "cr-1", 2.0, 100.0,
            new Targeting(null, null, null, Set.of("auto-intender")));
    private static final Campaign EVERYONE = new Campaign("everyone", "cr-2", 1.0, 100.0, null);

    private final TargetingIndex index = new TargetingIndex(List.of(AUTO_AUDIENCE, EVERYONE));
    private final BidRequest request = new BidRequest("r1",
            List.of(new Imp("1", new Banner(300, 250), 0.10, "USD")), null, null, null, 100, null);

    private List<String> matchIds(Set<String> segments) {
        return index.match(request, request.imp().get(0), segments).stream().map(Campaign::id).toList();
    }

    @Test
    void audienceCampaignMatchesOnlyUsersInItsSegment() {
        assertEquals(List.of("auto", "everyone"), matchIds(Set.of("auto-intender")));
        assertEquals(List.of("everyone"), matchIds(Set.of("gamer")));
    }

    @Test
    void unknownProfileOnlyMatchesCampaignsWithoutAudienceTargeting() {
        assertEquals(List.of("everyone"), matchIds(Set.of()));
    }
}
