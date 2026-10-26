package com.sakshichavan.rtb.bidder.campaign;

import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Device;
import com.sakshichavan.rtb.common.openrtb.Geo;
import com.sakshichavan.rtb.common.openrtb.Imp;
import com.sakshichavan.rtb.common.openrtb.Site;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TargetingIndexTest {

    private static final Banner MEDIUM_RECTANGLE = new Banner(300, 250);
    private static final Banner LEADERBOARD = new Banner(728, 90);

    private static Campaign campaign(String id, Set<Banner> sizes, Set<String> countries, Set<String> categories) {
        return new Campaign(id, "cr-" + id, 1.0, 100.0, new Targeting(sizes, countries, categories));
    }

    private static BidRequest request(Banner size, String country, List<String> categories) {
        Imp imp = new Imp("1", size, 0.10, "USD");
        Site site = new Site("site-1", "example.com", categories);
        Device device = (country == null) ? null : new Device("ua", "203.0.113.1", new Geo(country, null));
        return new BidRequest("req-1", List.of(imp), site, device, null, 100, null);
    }

    private static List<String> matchIds(TargetingIndex index, BidRequest request) {
        return index.match(request, request.imp().get(0)).stream().map(Campaign::id).toList();
    }

    @Test
    void matchesWhenAllDimensionsMatch() {
        TargetingIndex index = new TargetingIndex(List.of(
                campaign("sports", Set.of(MEDIUM_RECTANGLE), Set.of("USA"), Set.of("IAB17"))));

        assertEquals(List.of("sports"), matchIds(index, request(MEDIUM_RECTANGLE, "USA", List.of("IAB17"))));
    }

    @Test
    void untargetedCampaignMatchesAnyRequest() {
        TargetingIndex index = new TargetingIndex(List.of(campaign("everyone", Set.of(), Set.of(), Set.of())));

        assertEquals(List.of("everyone"), matchIds(index, request(LEADERBOARD, "BRA", List.of("IAB1"))));
        assertEquals(List.of("everyone"), matchIds(index, request(MEDIUM_RECTANGLE, null, List.of())));
    }

    @Test
    void excludesCampaignWhenAnyDimensionMisses() {
        TargetingIndex index = new TargetingIndex(List.of(
                campaign("sports", Set.of(MEDIUM_RECTANGLE), Set.of("USA"), Set.of("IAB17"))));

        assertEquals(List.of(), matchIds(index, request(LEADERBOARD, "USA", List.of("IAB17"))));
        assertEquals(List.of(), matchIds(index, request(MEDIUM_RECTANGLE, "DEU", List.of("IAB17"))));
        assertEquals(List.of(), matchIds(index, request(MEDIUM_RECTANGLE, "USA", List.of("IAB12"))));
    }

    @Test
    void unknownCountryOnlyMatchesCampaignsWithoutCountryTargeting() {
        TargetingIndex index = new TargetingIndex(List.of(
                campaign("us-only", Set.of(), Set.of("USA"), Set.of()),
                campaign("global", Set.of(), Set.of(), Set.of())));

        assertEquals(List.of("global"), matchIds(index, request(MEDIUM_RECTANGLE, null, List.of())));
    }

    @Test
    void matchesWhenAnyRequestCategoryOverlaps() {
        TargetingIndex index = new TargetingIndex(List.of(
                campaign("games", Set.of(), Set.of(), Set.of("IAB9")),
                campaign("arts", Set.of(), Set.of(), Set.of("IAB1")),
                campaign("news", Set.of(), Set.of(), Set.of("IAB12"))));

        assertEquals(List.of("games", "arts"),
                matchIds(index, request(MEDIUM_RECTANGLE, "USA", List.of("IAB9", "IAB1"))));
    }
}
