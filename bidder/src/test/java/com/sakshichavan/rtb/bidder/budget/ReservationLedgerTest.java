package com.sakshichavan.rtb.bidder.budget;

import com.sakshichavan.rtb.bidder.campaign.Campaign;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationLedgerTest {

    private static final long CHUNK = 100_000;

    private final AtomicLong now = new AtomicLong(0);
    private CampaignBudgets budgets;
    private ReservationLedger ledger;

    @BeforeEach
    void setUp() {
        budgets = new CampaignBudgets(new InMemoryBudgetSource(), CHUNK, 20_000, now::get);
        budgets.initialize(List.of(new Campaign("c1", "cr-1", 2.0, 1.00, null))).toCompletableFuture().join();
        ledger = new ReservationLedger(budgets, "http://bidder:8080", Duration.ofSeconds(60), now::get);
    }

    private void bid(String bidId, long micros) {
        assertTrue(budgets.tryReserve("c1", micros));
        ledger.record(bidId, "c1", micros);
    }

    private void assertBudgetBalances() {
        BudgetStats stats = budgets.stats().get("c1");
        assertEquals(stats.grantedMicros(), stats.reservedMicros() + stats.leaseRemainingMicros());
    }

    @Test
    void winChargesTheClearingPriceAndReleasesTheRest() {
        bid("b1", 2_000);

        ledger.win("b1", 1.00);

        assertEquals(CHUNK - 1_000, budgets.localRemainingMicros("c1"));
        assertEquals(1, ledger.stats().wins());
        assertEquals(1_000, ledger.stats().spentMicros());
        assertBudgetBalances();
    }

    @Test
    void lossReleasesTheWholeReservation() {
        bid("b1", 2_000);

        ledger.loss("b1");

        assertEquals(CHUNK, budgets.localRemainingMicros("c1"));
        assertEquals(1, ledger.stats().losses());
        assertBudgetBalances();
    }

    @Test
    void duplicateNoticesSettleExactlyOnce() {
        bid("b1", 2_000);

        ledger.win("b1", 1.00);
        ledger.win("b1", 1.00);
        ledger.loss("b1");

        assertEquals(CHUNK - 1_000, budgets.localRemainingMicros("c1"));
        assertEquals(1, ledger.stats().wins());
        assertEquals(2, ledger.stats().unmatched());
        assertEquals(1_000, ledger.stats().spentMicros());
    }

    @Test
    void neverChargesMoreThanWasReserved() {
        bid("b1", 2_000);

        ledger.win("b1", 50.00);

        assertEquals(2_000, ledger.stats().spentMicros());
        assertEquals(CHUNK - 2_000, budgets.localRemainingMicros("c1"));
    }

    @Test
    void reservationsWithNoNoticeExpireAndAreReleased() {
        bid("b1", 2_000);

        now.addAndGet(TimeUnit.SECONDS.toNanos(61));
        ledger.cleanUp();

        assertEquals(CHUNK, budgets.localRemainingMicros("c1"));
        assertEquals(1, ledger.stats().expired());
        assertEquals(0, ledger.stats().pending());
        assertBudgetBalances();
    }

    @Test
    void noticeUrlsCarryTheBidIdAndThePriceMacro() {
        assertEquals("http://bidder:8080/win?bid=req-1-imp-1&price=${AUCTION_PRICE}", ledger.winUrl("req-1-imp-1"));
        assertEquals("http://bidder:8080/loss?bid=req-1-imp-1", ledger.lossUrl("req-1-imp-1"));
    }
}
