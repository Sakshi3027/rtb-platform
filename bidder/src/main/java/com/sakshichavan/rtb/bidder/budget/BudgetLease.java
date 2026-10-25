package com.sakshichavan.rtb.bidder.budget;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A chunk of one campaign's budget leased to this bidder instance, spent locally with no network calls.
 *
 * Thread-safe and lock-free: reservations use a compare-and-swap loop, so event loop threads never block.
 * Refills are single-flight: only one thread at a time may fetch more budget, avoiding a thundering herd.
 */
public final class BudgetLease {

    private final AtomicLong remainingMicros;
    private final AtomicBoolean refillInFlight = new AtomicBoolean(false);

    public BudgetLease(long initialMicros) {
        if (initialMicros < 0) {
            throw new IllegalArgumentException("initial lease cannot be negative: " + initialMicros);
        }
        this.remainingMicros = new AtomicLong(initialMicros);
    }

    /** Reserves the amount if the lease can cover it. Never lets the balance go negative. */
    public boolean tryReserve(long amountMicros) {
        requirePositive(amountMicros);
        while (true) {
            long current = remainingMicros.get();
            if (current < amountMicros) {
                return false;
            }
            if (remainingMicros.compareAndSet(current, current - amountMicros)) {
                return true;
            }
        }
    }

    /** Returns budget to the lease, e.g. when a reserved bid loses the auction. */
    public void release(long amountMicros) {
        requirePositive(amountMicros);
        remainingMicros.addAndGet(amountMicros);
    }

    public long remainingMicros() {
        return remainingMicros.get();
    }

    public boolean isBelow(long thresholdMicros) {
        return remainingMicros.get() < thresholdMicros;
    }

    /** Returns true for exactly one caller until completeRefill is called. */
    public boolean tryBeginRefill() {
        return refillInFlight.compareAndSet(false, true);
    }

    /** Adds newly granted budget (which may be zero if the campaign is exhausted) and ends the refill. */
    public void completeRefill(long grantedMicros) {
        if (grantedMicros < 0) {
            throw new IllegalArgumentException("granted budget cannot be negative: " + grantedMicros);
        }
        if (grantedMicros > 0) {
            remainingMicros.addAndGet(grantedMicros);
        }
        refillInFlight.set(false);
    }

    private static void requirePositive(long amountMicros) {
        if (amountMicros <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amountMicros);
        }
    }
}
