package com.sakshichavan.rtb.common.features;

import com.sakshichavan.rtb.common.openrtb.AuctionType;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Device;
import com.sakshichavan.rtb.common.openrtb.Geo;
import com.sakshichavan.rtb.common.openrtb.Imp;
import com.sakshichavan.rtb.common.openrtb.Site;
import com.sakshichavan.rtb.common.openrtb.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ImpressionFeaturesTest {

    private static final Imp IMP = new Imp("1", new Banner(300, 250), 0.50, "USD");

    @Test
    void extractsEveryFeatureWithSegmentsSorted() {
        BidRequest request = new BidRequest("r1", List.of(IMP),
                new Site("s1", "sports.example.com", List.of("IAB17", "IAB1")),
                new Device("ua", "1.2.3.4", new Geo("USA", null)),
                new User("u1"), 100, AuctionType.FIRST_PRICE);

        ImpressionFeatures features = ImpressionFeatures.of(request, IMP, "c-sports-us", Set.of("traveler", "foodie"));

        assertEquals(new ImpressionFeatures("c-sports-us", "300x250", "USA", "IAB17",
                List.of("foodie", "traveler"), true), features);
    }

    @Test
    void missingFieldsBecomeUnknownAndAnonymousUsersHaveNoSegments() {
        BidRequest request = new BidRequest("r2", List.of(IMP), null, null, null, 100, AuctionType.FIRST_PRICE);

        ImpressionFeatures features = ImpressionFeatures.of(request, IMP, "c-news-global", Set.of("traveler"));

        assertEquals(ImpressionFeatures.UNKNOWN, features.country());
        assertEquals(ImpressionFeatures.UNKNOWN, features.category());
        assertEquals(List.of(), features.segments());
        assertFalse(features.knownUser());
    }
}
