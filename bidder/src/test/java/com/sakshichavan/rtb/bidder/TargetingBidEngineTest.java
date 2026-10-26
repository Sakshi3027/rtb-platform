package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.budget.SpendGuard;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.Targeting;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.common.openrtb.AuctionType;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;
import com.sakshichavan.rtb.common.openrtb.Imp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetingBidEngineTest {

    private static BidRequest request(double floor, AuctionType auctionType) {
        Imp imp = new Imp("imp-1", new Banner(300, 250), floor, "USD");
        return new BidRequest("req-1", List.of(imp), null, null, null, 100, auctionType);
    }

    private static Campaign campaign(String id, double maxBidCpm) {
        return new Campaign(id, "cr-" + id, maxBidCpm, 100.0, null);
    }

    private static TargetingBidEngine engineWith(SpendGuard guard, Campaign... campaigns) {
        return new TargetingBidEngine(new TargetingIndex(List.of(campaigns)), new BidPricer(0.5), guard);
    }

    private static Bid onlyBid(BidResponse response) {
        return response.seatBid().get(0).bid().get(0);
    }

    @Test
    void secondPriceBidsHighestValueCampaignAtFullValue() {
        TargetingBidEngine engine = engineWith(SpendGuard.UNLIMITED, campaign("cheap", 1.00), campaign("premium", 2.50));

        BidResponse response = engine.evaluate(request(0.50, AuctionType.SECOND_PRICE)).orElseThrow();

        assertEquals("premium", onlyBid(response).adId());
        assertEquals(2.50, onlyBid(response).price());
    }

    @Test
    void firstPriceShadesTheBid() {
        TargetingBidEngine engine = engineWith(SpendGuard.UNLIMITED, campaign("premium", 2.50));

        BidResponse response = engine.evaluate(request(0.50, AuctionType.FIRST_PRICE)).orElseThrow();

        assertEquals(1.50, onlyBid(response).price());
    }

    @Test
    void skipsCampaignsThatCannotClearTheFloor() {
        TargetingBidEngine engine = engineWith(SpendGuard.UNLIMITED, campaign("cheap", 1.00), campaign("premium", 2.50));

        Optional<BidResponse> response = engine.evaluate(request(3.00, AuctionType.FIRST_PRICE));

        assertTrue(response.isEmpty());
    }

    @Test
    void producesResponsesTheExchangeAccepts() {
        TargetingBidEngine engine = engineWith(SpendGuard.UNLIMITED, campaign("only", 1.50));
        BidRequest request = request(0.75, AuctionType.FIRST_PRICE);

        BidResponse response = engine.evaluate(request).orElseThrow();

        assertTrue(response.isValidFor(request));
        assertEquals(TargetingBidEngine.SEAT, response.seatBid().get(0).seat());
    }

    @Test
    void noBidWhenNoCampaignTargetsTheRequest() {
        Campaign usOnly = new Campaign("us-only", "cr-1", 2.00, 100.0, new Targeting(null, Set.of("USA"), null));
        TargetingBidEngine engine = engineWith(SpendGuard.UNLIMITED, usOnly);

        assertTrue(engine.evaluate(request(0.10, AuctionType.FIRST_PRICE)).isEmpty());
    }

    @Test
    void fallsBackToNextBestCampaignWhenBudgetIsExhausted() {
        SpendGuard premiumIsBroke = (campaignId, micros) -> !campaignId.equals("premium");
        TargetingBidEngine engine = engineWith(premiumIsBroke, campaign("cheap", 1.00), campaign("premium", 2.50));

        BidResponse response = engine.evaluate(request(0.50, AuctionType.SECOND_PRICE)).orElseThrow();

        assertEquals("cheap", onlyBid(response).adId());
    }

    @Test
    void noBidWhenEveryCampaignIsOutOfBudget() {
        SpendGuard everyoneIsBroke = (campaignId, micros) -> false;
        TargetingBidEngine engine = engineWith(everyoneIsBroke, campaign("cheap", 1.00), campaign("premium", 2.50));

        assertTrue(engine.evaluate(request(0.50, AuctionType.FIRST_PRICE)).isEmpty());
    }

    @Test
    void reservesThePerImpressionCostNotTheCpm() {
        List<Long> reserved = new ArrayList<>();
        SpendGuard recording = (campaignId, micros) -> {
            reserved.add(micros);
            return true;
        };
        TargetingBidEngine engine = engineWith(recording, campaign("premium", 2.50));

        engine.evaluate(request(0.50, AuctionType.FIRST_PRICE)).orElseThrow();

        assertEquals(List.of(1_500L), reserved);
    }
}
