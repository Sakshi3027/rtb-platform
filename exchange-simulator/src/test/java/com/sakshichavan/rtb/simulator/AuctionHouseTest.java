package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.openrtb.AuctionType;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;
import com.sakshichavan.rtb.common.openrtb.Imp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionHouseTest {

    private static final String NURL = "http://bidder/win?bid=b1&price=${AUCTION_PRICE}";
    private static final String LURL = "http://bidder/loss?bid=b1";

    private static BidRequest request(double floor, AuctionType auctionType) {
        return new BidRequest("r1", List.of(new Imp("1", new Banner(300, 250), floor, "USD")),
                null, null, null, 100, auctionType);
    }

    private static BidResponse response(double price) {
        return BidResponse.single("r1", "seat", new Bid("b1", "1", price, "ad", "cr", NURL, LURL));
    }

    @Test
    void firstPriceWinnerPaysItsOwnBid() {
        AuctionHouse.Plan plan = new AuctionHouse.Plan(1.20, true, false);

        AuctionHouse.Outcome outcome = AuctionHouse.settle(plan, request(0.50, AuctionType.FIRST_PRICE), response(1.50));

        assertTrue(outcome.won());
        assertEquals(1.50, outcome.clearingCpm());
        assertEquals(List.of("http://bidder/win?bid=b1&price=1.5000"), outcome.noticeUrls());
    }

    @Test
    void secondPriceWinnerPaysTheCompetingBidPlusOneCent() {
        AuctionHouse.Plan plan = new AuctionHouse.Plan(1.20, true, false);

        AuctionHouse.Outcome outcome = AuctionHouse.settle(plan, request(0.50, AuctionType.SECOND_PRICE), response(3.00));

        assertEquals(1.21, outcome.clearingCpm());
    }

    @Test
    void secondPriceNeverChargesMoreThanTheBid() {
        AuctionHouse.Plan plan = new AuctionHouse.Plan(1.495, true, false);

        AuctionHouse.Outcome outcome = AuctionHouse.settle(plan, request(0.50, AuctionType.SECOND_PRICE), response(1.50));

        assertEquals(1.50, outcome.clearingCpm());
    }

    @Test
    void losingBidGetsALossNoticeOnlyWhenPlanned() {
        AuctionHouse.Outcome reported = AuctionHouse.settle(
                new AuctionHouse.Plan(2.00, true, false), request(0.50, AuctionType.FIRST_PRICE), response(1.50));
        AuctionHouse.Outcome silent = AuctionHouse.settle(
                new AuctionHouse.Plan(2.00, false, false), request(0.50, AuctionType.FIRST_PRICE), response(1.50));

        assertFalse(reported.won());
        assertEquals(List.of(LURL), reported.noticeUrls());
        assertTrue(silent.noticeUrls().isEmpty());
    }

    @Test
    void duplicateNoticesAreSentTwice() {
        AuctionHouse.Outcome outcome = AuctionHouse.settle(
                new AuctionHouse.Plan(1.00, true, true), request(0.50, AuctionType.FIRST_PRICE), response(1.50));

        assertEquals(2, outcome.noticeUrls().size());
        assertEquals(1, outcome.duplicates());
    }

    @Test
    void competingBidsAreDeterministicAndCenteredNearFloorTimesEToTheMu() {
        AuctionHouse first = new AuctionHouse(7, 0.4, 0.5, 0.9, 0.05);
        AuctionHouse second = new AuctionHouse(7, 0.4, 0.5, 0.9, 0.05);
        BidRequest request = request(1.00, AuctionType.FIRST_PRICE);

        List<Double> draws = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            AuctionHouse.Plan plan = first.plan(request);
            assertEquals(plan, second.plan(request));
            draws.add(plan.competingCpm());
        }
        Collections.sort(draws);
        double median = draws.get(draws.size() / 2);

        assertTrue(median > 1.40 && median < 1.60, "median competing bid was " + median);
    }
}
