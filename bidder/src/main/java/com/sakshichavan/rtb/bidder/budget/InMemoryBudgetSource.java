package com.sakshichavan.rtb.bidder.budget;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Single-process BudgetSource for tests and running without Redis. Lock-free, same semantics as Redis. */
public final class InMemoryBudgetSource implements BudgetSource {

    private final Map<String, AtomicLong> budgets = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<Long> lease(String campaignId, long requestedMicros) {
        requirePositive(requestedMicros);
        AtomicLong budget = budgets.get(campaignId);
        if (budget == null) {
            return CompletableFuture.completedFuture(0L);
        }
        while (true) {
            long current = budget.get();
            if (current <= 0) {
                return CompletableFuture.completedFuture(0L);
            }
            long grant = Math.min(current, requestedMicros);
            if (budget.compareAndSet(current, current - grant)) {
                return CompletableFuture.completedFuture(grant);
            }
        }
    }

    @Override
    public CompletionStage<Boolean> initialize(String campaignId, long budgetMicros) {
        boolean created = budgets.putIfAbsent(campaignId, new AtomicLong(budgetMicros)) == null;
        return CompletableFuture.completedFuture(created);
    }

    private static void requirePositive(long micros) {
        if (micros <= 0) {
            throw new IllegalArgumentException("requested amount must be positive: " + micros);
        }
    }
}
