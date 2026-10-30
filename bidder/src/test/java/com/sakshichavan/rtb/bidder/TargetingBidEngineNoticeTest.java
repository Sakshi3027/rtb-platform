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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetingBidEngineNoticeTest {

    private static final BidRequest REQUEST = new BidRequest("req-1",
            List.of(new Imp("imp-1", new Banner(300, 250), 0.50, "USD")), null, null, null, 100, AuctionType.SECOND_PRICE);

    private CampaignBudgets budgets;
    private ReservationLedger ledger;
    private TargetingBidEngine engine;

    @BeforeEach
    void setUp() {
        Campaign campaign = new Campaign("c1", "cr-1", 2.00, 1.00, null);
        budgets = new CampaignBudgets(new InMemoryBudgetSource(), 100_000, 20_000, System::nanoTime);
        budgets.initialize(List.of(campaign)).toCompletableFuture().join();
        ledger = new ReservationLedger(budgets, "http://bidder:8080", Duration.ofSeconds(60), System::nanoTime);
        engine = new TargetingBidEngine(new TargetingIndex(List.of(campaign)),
                new BidPricer(0.5), budgets, FrequencyGuard.UNLIMITED, ledger);
    }

    private Bid bid() {
        return engine.evaluate(REQUEST).orElseThrow().seatBid().get(0).bid().get(0);
    }

    @Test
    void recordsTheReservationAndAttachesNoticeUrlsForTheBidderGeneratedId() {
        Bid bid = bid();

        assertTrue(bid.id().startsWith("b-"), "bid ids come from the bidder, not the exchange: " + bid.id());
        assertEquals("imp-1", bid.impId());
        assertEquals("http://bidder:8080/win?bid=" + bid.id() + "&price=${AUCTION_PRICE}", bid.nurl());
        assertEquals("http://bidder:8080/loss?bid=" + bid.id(), bid.lurl());
        assertEquals(1, ledger.stats().pending());
        assertEquals(100_000 - 2_000, budgets.localRemainingMicros("c1"));

        ledger.loss(bid.id());

        assertEquals(100_000, budgets.localRemainingMicros("c1"));
    }

    @Test
    void theSameRequestIdTwiceGetsTwoIndependentBids() {
        Bid first = bid();
        Bid second = bid();

        assertNotEquals(first.id(), second.id(), "a repeated exchange request id must not collide");
        assertEquals(2, ledger.stats().pending(), "both reservations must be tracked; neither may overwrite the other");

        ledger.loss(first.id());
        ledger.loss(second.id());

        assertEquals(100_000, budgets.localRemainingMicros("c1"), "every reserved micro must come back");
        assertEquals(2, ledger.stats().losses());
    }
}
