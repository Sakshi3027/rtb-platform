package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.budget.CampaignBudgets;
import com.sakshichavan.rtb.bidder.budget.InMemoryBudgetSource;
import com.sakshichavan.rtb.bidder.budget.ReservationLedger;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.bidder.frequency.FrequencyGuard;
import com.sakshichavan.rtb.common.openrtb.AuctionType;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Imp;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TargetingBidEngineNoticeTest {

    @Test
    void recordsTheReservationAndAttachesNoticeUrls() {
        Campaign campaign = new Campaign("c1", "cr-1", 2.00, 1.00, null);
        CampaignBudgets budgets = new CampaignBudgets(new InMemoryBudgetSource(), 100_000, 20_000, System::nanoTime);
        budgets.initialize(List.of(campaign)).toCompletableFuture().join();
        ReservationLedger ledger = new ReservationLedger(budgets, "http://bidder:8080", Duration.ofSeconds(60), System::nanoTime);
        TargetingBidEngine engine = new TargetingBidEngine(new TargetingIndex(List.of(campaign)),
                new BidPricer(0.5), budgets, FrequencyGuard.UNLIMITED, ledger);
        BidRequest request = new BidRequest("req-1",
                List.of(new Imp("imp-1", new Banner(300, 250), 0.50, "USD")), null, null, null, 100, AuctionType.SECOND_PRICE);

        Bid bid = engine.evaluate(request).orElseThrow().seatBid().get(0).bid().get(0);

        assertEquals("http://bidder:8080/win?bid=req-1-imp-1&price=${AUCTION_PRICE}", bid.nurl());
        assertEquals("http://bidder:8080/loss?bid=req-1-imp-1", bid.lurl());
        assertEquals(1, ledger.stats().pending());
        assertEquals(100_000 - 2_000, budgets.localRemainingMicros("c1"));

        ledger.loss(bid.id());

        assertEquals(100_000, budgets.localRemainingMicros("c1"));
    }
}
