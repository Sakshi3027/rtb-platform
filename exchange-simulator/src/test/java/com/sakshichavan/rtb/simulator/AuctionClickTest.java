package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.features.ImpressionFeatures;
import com.sakshichavan.rtb.common.openrtb.AuctionType;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;
import com.sakshichavan.rtb.common.openrtb.Imp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionClickTest {

    private static final BidRequest REQUEST = new BidRequest("r1",
            List.of(new Imp("1", new Banner(300, 250), 0.50, "USD")), null, null, null, 100, AuctionType.FIRST_PRICE);
    private static final BidResponse WINNING_BID = BidResponse.single("r1", "seat",
            new Bid("b1", "1", 1.50, "c-sports-us", "cr", null, null));

    @Test
    void aWonImpressionIsClickedExactlyWhenItsDrawFallsBelowTheTrueCtr() {
        double trueCtr = ClickModel.probability(new ImpressionFeatures(
                "c-sports-us", "300x250", ImpressionFeatures.UNKNOWN, ImpressionFeatures.UNKNOWN, List.of(), false));

        AuctionHouse.Outcome lucky = AuctionHouse.settle(
                new AuctionHouse.Plan(1.00, true, false, trueCtr / 2), REQUEST, WINNING_BID);
        AuctionHouse.Outcome unlucky = AuctionHouse.settle(
                new AuctionHouse.Plan(1.00, true, false, trueCtr * 2), REQUEST, WINNING_BID);
        AuctionHouse.Outcome lost = AuctionHouse.settle(
                new AuctionHouse.Plan(9.00, true, false, 0.0), REQUEST, WINNING_BID);

        assertEquals(trueCtr, lucky.trueCtr(), 1e-12);
        assertTrue(lucky.clicked());
        assertFalse(unlucky.clicked());
        assertFalse(lost.clicked(), "a lost impression is never shown, so it is never clicked");
    }
}
