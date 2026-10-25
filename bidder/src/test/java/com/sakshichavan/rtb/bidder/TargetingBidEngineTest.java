package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;
import com.sakshichavan.rtb.common.openrtb.Imp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetingBidEngineTest {

    private static BidRequest requestWithFloor(double floor) {
        Imp imp = new Imp("imp-1", new Banner(300, 250), floor, "USD");
        return new BidRequest("req-1", List.of(imp), null, null, null, 100, null);
    }

    private static TargetingBidEngine engineWith(Campaign... campaigns) {
        return new TargetingBidEngine(new TargetingIndex(List.of(campaigns)));
    }

    private static Bid onlyBid(BidResponse response) {
        return response.seatBid().get(0).bid().get(0);
    }

    @Test
    void bidsWithHighestPayingMatchingCampaign() {
        TargetingBidEngine engine = engineWith(
                new Campaign("cheap", "cr-1", 1.00, null),
                new Campaign("premium", "cr-2", 2.50, null));

        BidResponse response = engine.evaluate(requestWithFloor(0.50)).orElseThrow();

        assertEquals("premium", onlyBid(response).adId());
        assertEquals(2.50, onlyBid(response).price());
    }

    @Test
    void skipsCampaignsThatCannotClearTheFloor() {
        TargetingBidEngine engine = engineWith(
                new Campaign("cheap", "cr-1", 1.00, null),
                new Campaign("premium", "cr-2", 2.50, null));

        Optional<BidResponse> response = engine.evaluate(requestWithFloor(3.00));

        assertTrue(response.isEmpty());
    }

    @Test
    void producesResponsesTheExchangeAccepts() {
        TargetingBidEngine engine = engineWith(new Campaign("only", "cr-1", 1.50, null));
        BidRequest request = requestWithFloor(0.75);

        BidResponse response = engine.evaluate(request).orElseThrow();

        assertTrue(response.isValidFor(request));
        assertEquals(TargetingBidEngine.SEAT, response.seatBid().get(0).seat());
    }

    @Test
    void noBidWhenNoCampaignTargetsTheRequest() {
        Campaign usOnly = new Campaign("us-only", "cr-1", 2.00,
                new com.sakshichavan.rtb.bidder.campaign.Targeting(null, java.util.Set.of("USA"), null));
        TargetingBidEngine engine = engineWith(usOnly);

        assertTrue(engine.evaluate(requestWithFloor(0.10)).isEmpty());
    }
}
