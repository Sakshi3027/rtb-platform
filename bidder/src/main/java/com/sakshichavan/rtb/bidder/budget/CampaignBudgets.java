package com.sakshichavan.rtb.bidder.budget;

import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.common.money.Micros;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Per-campaign budget leasing. Bids reserve from a local lease with no network calls.
 * When a lease drops below the low-water mark, one thread refills it asynchronously from the shared
 * BudgetSource, so the refill usually lands before the lease runs dry.
 * Exhausted campaigns and failed refills back off for one second to avoid hammering the source.
 * Counts granted and reserved micros per campaign so spend can be reconciled across a fleet.
 */
public final class CampaignBudgets implements SpendGuard {

    private static final long BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final BudgetSource source;
    private final long chunkMicros;
    private final long lowWaterMicros;
    private final LongSupplier nanoClock;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    public CampaignBudgets(BudgetSource source, long chunkMicros, long lowWaterMicros, LongSupplier nanoClock) {
        if (chunkMicros <= 0) {
            throw new IllegalArgumentException("lease chunk must be positive: " + chunkMicros);
        }
        if (lowWaterMicros < 0 || lowWaterMicros >= chunkMicros) {
            throw new IllegalArgumentException("low-water mark must be in [0, chunk): " + lowWaterMicros);
        }
        this.source = source;
        this.chunkMicros = chunkMicros;
        this.lowWaterMicros = lowWaterMicros;
        this.nanoClock = nanoClock;
    }

    /** Sets each campaign's daily budget (if not already set today) and primes its first lease. */
    public CompletionStage<Void> initialize(Collection<Campaign> campaigns) {
        List<CompletableFuture<Void>> primed = new ArrayList<>();
        for (Campaign campaign : campaigns) {
            Slot slot = new Slot(nanoClock.getAsLong());
            slots.put(campaign.id(), slot);
            slot.lease.tryBeginRefill();
            long budgetMicros = Micros.fromDollars(campaign.dailyBudgetUsd());
            primed.add(source.initialize(campaign.id(), budgetMicros)
                    .thenCompose(created -> refill(campaign.id(), slot))
                    .toCompletableFuture());
        }
        return CompletableFuture.allOf(primed.toArray(CompletableFuture[]::new));
    }

    @Override
    public boolean tryReserve(String campaignId, long micros) {
        Slot slot = slots.get(campaignId);
        if (slot == null) {
            return false;
        }
        boolean reserved = slot.lease.tryReserve(micros);
        if (reserved) {
            slot.reservedMicros.add(micros);
        }
        if (slot.lease.isBelow(lowWaterMicros)
                && nanoClock.getAsLong() - slot.retryAfterNanos >= 0
                && slot.lease.tryBeginRefill()) {
            refill(campaignId, slot);
        }
        return reserved;
    }

    public long localRemainingMicros(String campaignId) {
        Slot slot = slots.get(campaignId);
        return (slot == null) ? 0 : slot.lease.remainingMicros();
    }

    /** Snapshot of per-campaign accounting, sorted by campaign id. Exact when the bidder is idle. */
    public Map<String, BudgetStats> stats() {
        Map<String, BudgetStats> snapshot = new TreeMap<>();
        slots.forEach((campaignId, slot) -> snapshot.put(campaignId, new BudgetStats(
                slot.grantedMicros.sum(), slot.reservedMicros.sum(), slot.lease.remainingMicros())));
        return snapshot;
    }

    public long chunkMicros() {
        return chunkMicros;
    }

    public long lowWaterMicros() {
        return lowWaterMicros;
    }

    private CompletionStage<Void> refill(String campaignId, Slot slot) {
        CompletionStage<Long> granted;
        try {
            granted = source.lease(campaignId, chunkMicros);
        } catch (RuntimeException e) {
            granted = CompletableFuture.failedFuture(e);
        }
        return granted.handle((amount, error) -> {
            long micros = (error == null && amount != null) ? amount : 0L;
            if (micros > 0) {
                slot.grantedMicros.add(micros);
            } else {
                slot.retryAfterNanos = nanoClock.getAsLong() + BACKOFF_NANOS;
            }
            slot.lease.completeRefill(micros);
            return null;
        });
    }

    private static final class Slot {
        final BudgetLease lease = new BudgetLease(0);
        final LongAdder grantedMicros = new LongAdder();
        final LongAdder reservedMicros = new LongAdder();
        volatile long retryAfterNanos;

        Slot(long nowNanos) {
            this.retryAfterNanos = nowNanos;
        }
    }
}
