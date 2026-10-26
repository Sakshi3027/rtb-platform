package com.sakshichavan.rtb.bidder.budget;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Single-process BudgetSource for tests and running without Redis. Lock-free, same semantics as Redis. */
public final class InMemoryBudgetSource implements BudgetSource {

    private final Map<String, Budget> budgets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final PacingPolicy pacing;

    public InMemoryBudgetSource() {
        this(Clock.systemUTC(), PacingPolicy.NONE);
    }

    public InMemoryBudgetSource(Clock clock, PacingPolicy pacing) {
        this.clock = clock;
        this.pacing = pacing;
    }

    @Override
    public CompletionStage<Long> lease(String campaignId, long requestedMicros) {
        if (requestedMicros <= 0) {
            throw new IllegalArgumentException("requested amount must be positive: " + requestedMicros);
        }
        Budget budget = budgets.get(campaignId);
        if (budget == null) {
            return CompletableFuture.completedFuture(0L);
        }
        long allowed = Math.min(budget.total, (long) Math.floor(budget.total * pacing.allowedFraction(clock.instant())));
        while (true) {
            long spent = budget.spent.get();
            long available = allowed - spent;
            if (available <= 0) {
                return CompletableFuture.completedFuture(0L);
            }
            long grant = Math.min(available, requestedMicros);
            if (budget.spent.compareAndSet(spent, spent + grant)) {
                return CompletableFuture.completedFuture(grant);
            }
        }
    }

    @Override
    public CompletionStage<Boolean> initialize(String campaignId, long budgetMicros) {
        boolean created = budgets.putIfAbsent(campaignId, new Budget(budgetMicros)) == null;
        return CompletableFuture.completedFuture(created);
    }

    private static final class Budget {
        final long total;
        final AtomicLong spent = new AtomicLong();

        Budget(long total) {
            this.total = total;
        }
    }
}
