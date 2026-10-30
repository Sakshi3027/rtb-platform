package com.sakshichavan.rtb.bidder.events;

import com.sakshichavan.rtb.bidder.budget.CampaignBudgets;
import com.sakshichavan.rtb.bidder.budget.InMemoryBudgetSource;
import com.sakshichavan.rtb.bidder.budget.ReservationLedger;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.common.events.SettlementEvent;
import com.sakshichavan.rtb.common.events.SettlementType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerEventsTest {

    private final AtomicLong now = new AtomicLong(0);
    private final List<SettlementEvent> published = new CopyOnWriteArrayList<>();
    private CampaignBudgets budgets;
    private ReservationLedger ledger;

    @BeforeEach
    void setUp() {
        budgets = new CampaignBudgets(new InMemoryBudgetSource(), 100_000, 20_000, now::get);
        budgets.initialize(List.of(new Campaign("c1", "cr-1", 2.0, 1.00, null))).toCompletableFuture().join();
        ledger = new ReservationLedger(budgets, "http://bidder", Duration.ofSeconds(60), now::get, published::add);
    }

    private void bid(String bidId) {
        assertTrue(budgets.tryReserve("c1", 2_000));
        ledger.record(bidId, "c1", 2_000);
    }

    @Test
    void winPublishesOneEventWithTheCharge() {
        bid("b1");

        ledger.win("b1", 1.00);

        assertEquals(1, published.size());
        SettlementEvent event = published.get(0);
        assertEquals("settlement-b1", event.eventId());
        assertEquals(SettlementType.WIN, event.type());
        assertEquals("c1", event.campaignId());
        assertEquals(2_000, event.reservedMicros());
        assertEquals(1_000, event.chargedMicros());
    }

    @Test
    void duplicateNoticesPublishNothingMore() {
        bid("b1");

        ledger.win("b1", 1.00);
        ledger.win("b1", 1.00);
        ledger.loss("b1");

        assertEquals(1, published.size());
    }

    @Test
    void lossPublishesAZeroChargeEvent() {
        bid("b2");

        ledger.loss("b2");

        assertEquals(SettlementType.LOSS, published.get(0).type());
        assertEquals(0, published.get(0).chargedMicros());
    }

    @Test
    void expiryPublishesAnExpiredEvent() {
        bid("b3");

        now.addAndGet(TimeUnit.SECONDS.toNanos(61));
        ledger.cleanUp();

        assertEquals(1, published.size());
        assertEquals(SettlementType.EXPIRED, published.get(0).type());
        assertEquals("settlement-b3", published.get(0).eventId());
    }
}
