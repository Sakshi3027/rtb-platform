package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.budget.SpendGuard;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.bidder.frequency.FrequencyGuard;
import com.sakshichavan.rtb.common.openrtb.AuctionType;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Imp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetingBidEngineValuerTest {

    private static final Campaign EXPENSIVE = new Campaign("expensive", "cr-x", 3.00, 10.0, null);
    private static final Campaign RELEVANT = new Campaign("relevant", "cr-r", 2.00, 10.0, null);
    private static final BidRequest REQUEST = new BidRequest("r1",
            List.of(new Imp("1", new Banner(300, 250), 0.50, "USD")), null, null, null, 100, AuctionType.SECOND_PRICE);

    private static TargetingBidEngine engine(Map<String, Double> values) {
        CampaignValuer valuer = (request, imp, profile, campaigns) ->
                campaigns.stream().mapToDouble(campaign -> values.get(campaign.id())).toArray();
        return new TargetingBidEngine(new TargetingIndex(List.of(EXPENSIVE, RELEVANT)), new BidPricer(0.5),
                SpendGuard.UNLIMITED, FrequencyGuard.UNLIMITED, null, valuer);
    }

    @Test
    void bidsForTheCampaignWithTheHighestExpectedValueNotTheHighestPrice() {
        Bid bid = engine(Map.of("expensive", 1.00, "relevant", 5.00))
                .evaluate(REQUEST).orElseThrow().seatBid().get(0).bid().get(0);

        assertEquals("relevant", bid.adId());
        assertEquals(5.00, bid.price(), 1e-9, "second price bids the full expected value");
    }

    @Test
    void campaignsValuedBelowTheFloorDoNotBid() {
        assertTrue(engine(Map.of("expensive", 0.40, "relevant", 0.30)).evaluate(REQUEST).isEmpty());
    }
}
