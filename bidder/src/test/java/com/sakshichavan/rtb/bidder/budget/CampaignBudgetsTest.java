package com.sakshichavan.rtb.bidder.budget;

import com.sakshichavan.rtb.bidder.campaign.Campaign;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignBudgetsTest {

    private static final long CHUNK = 100_000;
    private static final long LOW_WATER = 20_000;
    private static final long PAST_BACKOFF = TimeUnit.SECONDS.toNanos(2);

    /** Fake clock: tests move time forward explicitly instead of sleeping. */
    private final AtomicLong now = new AtomicLong(0);

    private static Campaign campaign(String id, double dailyBudgetUsd) {
        return new Campaign(id, "cr-" + id, 2.0, dailyBudgetUsd, null);
    }

    private CampaignBudgets budgetsWith(BudgetSource source, Campaign... campaigns) {
        CampaignBudgets budgets = new CampaignBudgets(source, CHUNK, LOW_WATER, now::get);
        budgets.initialize(List.of(campaigns)).toCompletableFuture().join();
        return budgets;
    }

    @Test
    void reservesFromThePrimedLease() {
        CampaignBudgets budgets = budgetsWith(new InMemoryBudgetSource(), campaign("c1", 1.00));

        assertTrue(budgets.tryReserve("c1", 10_000));
        assertEquals(90_000, budgets.localRemainingMicros("c1"));
    }

    @Test
    void spendsExactlyTheDailyBudgetAcrossManyRefills() {
        CampaignBudgets budgets = budgetsWith(new InMemoryBudgetSource(), campaign("c1", 1.00));

        int reservations = 0;
        while (budgets.tryReserve("c1", 10_000)) {
            reservations++;
        }

        assertEquals(100, reservations);
        assertEquals(0, budgets.localRemainingMicros("c1"));
    }

    @Test
    void rejectsUnknownCampaigns() {
        CampaignBudgets budgets = budgetsWith(new InMemoryBudgetSource(), campaign("c1", 1.00));

        assertFalse(budgets.tryReserve("nope", 1_000));
    }

    @Test
    void backsOffInsteadOfHammeringAnExhaustedCampaign() {
        CountingSource source = new CountingSource(new InMemoryBudgetSource());
        CampaignBudgets budgets = budgetsWith(source, campaign("c1", 0.10));

        while (budgets.tryReserve("c1", 10_000)) {
            // drain the whole $0.10 budget
        }
        int callsWhenExhausted = source.leaseCalls.get();

        for (int i = 0; i < 50; i++) {
            budgets.tryReserve("c1", 10_000);
        }
        assertEquals(callsWhenExhausted, source.leaseCalls.get());

        now.addAndGet(PAST_BACKOFF);
        budgets.tryReserve("c1", 10_000);
        assertEquals(callsWhenExhausted + 1, source.leaseCalls.get());
    }

    @Test
    void survivesRedisFailuresWithoutThrowing() {
        FailingSource source = new FailingSource();
        CampaignBudgets budgets = budgetsWith(source, campaign("c1", 1.00));

        assertFalse(budgets.tryReserve("c1", 10_000));
        assertEquals(1, source.leaseCalls.get());

        now.addAndGet(PAST_BACKOFF);
        assertFalse(budgets.tryReserve("c1", 10_000));
        assertEquals(2, source.leaseCalls.get());
    }

    /** Wraps a real source and counts lease calls, to verify how often Redis would be hit. */
    private static final class CountingSource implements BudgetSource {
        private final BudgetSource delegate;
        private final AtomicInteger leaseCalls = new AtomicInteger();

        CountingSource(BudgetSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<Long> lease(String campaignId, long requestedMicros) {
            leaseCalls.incrementAndGet();
            return delegate.lease(campaignId, requestedMicros);
        }

        @Override
        public CompletionStage<Boolean> initialize(String campaignId, long budgetMicros) {
            return delegate.initialize(campaignId, budgetMicros);
        }
    }

    /** Simulates Redis being down: every lease fails. */
    private static final class FailingSource implements BudgetSource {
        private final AtomicInteger leaseCalls = new AtomicInteger();

        @Override
        public CompletionStage<Long> lease(String campaignId, long requestedMicros) {
            leaseCalls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("redis is down"));
        }

        @Override
        public CompletionStage<Boolean> initialize(String campaignId, long budgetMicros) {
            return CompletableFuture.completedFuture(true);
        }
    }
}
