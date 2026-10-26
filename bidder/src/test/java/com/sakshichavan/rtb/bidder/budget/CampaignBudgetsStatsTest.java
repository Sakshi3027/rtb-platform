package com.sakshichavan.rtb.bidder.budget;

import com.sakshichavan.rtb.bidder.campaign.Campaign;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CampaignBudgetsStatsTest {

    @Test
    void statsBalanceGrantedAgainstReservedPlusRemaining() {
        CampaignBudgets budgets = new CampaignBudgets(new InMemoryBudgetSource(), 100_000, 20_000, () -> 0L);
        budgets.initialize(List.of(new Campaign("c1", "cr-1", 2.0, 1.00, null))).toCompletableFuture().join();

        for (int i = 0; i < 3; i++) {
            budgets.tryReserve("c1", 10_000);
        }

        BudgetStats stats = budgets.stats().get("c1");
        assertEquals(100_000, stats.grantedMicros());
        assertEquals(30_000, stats.reservedMicros());
        assertEquals(70_000, stats.leaseRemainingMicros());
        assertEquals(stats.grantedMicros(), stats.reservedMicros() + stats.leaseRemainingMicros());
    }
}
