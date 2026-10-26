package com.sakshichavan.rtb.bidder.resilience;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Stops calling a failing dependency so requests fail fast instead of waiting on doomed calls.
 *
 * CLOSED:    calls go through; failures are counted over a tumbling time window.
 * OPEN:      entered when a window has at least minimumCalls and a failure rate at or above the threshold;
 *            calls are refused immediately until openDuration passes.
 * HALF_OPEN: exactly one probe call is allowed. Success closes the breaker; failure reopens it.
 *
 * The CLOSED fast path is a volatile read with no locking. Recording outcomes takes a lock for a few
 * operations; at 10k calls/s that lock is busy about 0.05% of the time, so contention is negligible
 * and the state machine stays simple to reason about.
 * Known simplification: a slow call started before the breaker opened can finish during HALF_OPEN
 * and be taken as the probe's result.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final double failureRateThreshold;
    private final int minimumCalls;
    private final long windowNanos;
    private final long openNanos;
    private final LongSupplier nanoClock;

    private volatile State state = State.CLOSED;
    private long windowStart;
    private int calls;
    private int failures;
    private long openedAt;
    private long timesOpened;

    public CircuitBreaker(double failureRateThreshold, int minimumCalls, Duration window, Duration openDuration,
                          LongSupplier nanoClock) {
        if (failureRateThreshold <= 0 || failureRateThreshold > 1) {
            throw new IllegalArgumentException("failure rate threshold must be in (0, 1]: " + failureRateThreshold);
        }
        if (minimumCalls <= 0) {
            throw new IllegalArgumentException("minimum calls must be positive: " + minimumCalls);
        }
        this.failureRateThreshold = failureRateThreshold;
        this.minimumCalls = minimumCalls;
        this.windowNanos = window.toNanos();
        this.openNanos = openDuration.toNanos();
        this.nanoClock = nanoClock;
        this.windowStart = nanoClock.getAsLong();
    }

    /** Opens at 50% failures over at least 20 calls in a 1-second window; probes again after 2 seconds. */
    public static CircuitBreaker withDefaults(LongSupplier nanoClock) {
        return new CircuitBreaker(0.5, 20, Duration.ofSeconds(1), Duration.ofSeconds(2), nanoClock);
    }

    /** True if the caller may make the call. Every permitted call must report onSuccess or onFailure. */
    public boolean tryAcquire() {
        if (state == State.CLOSED) {
            return true;
        }
        synchronized (this) {
            if (state == State.CLOSED) {
                return true;
            }
            if (state == State.OPEN && nanoClock.getAsLong() - openedAt >= openNanos) {
                state = State.HALF_OPEN;
                return true;
            }
            return false;
        }
    }

    public synchronized void onSuccess() {
        if (state == State.HALF_OPEN) {
            close();
        } else if (state == State.CLOSED) {
            record(false);
        }
    }

    public synchronized void onFailure() {
        if (state == State.HALF_OPEN) {
            open();
        } else if (state == State.CLOSED) {
            record(true);
        }
    }

    public State state() {
        return state;
    }

    public synchronized long timesOpened() {
        return timesOpened;
    }

    private void record(boolean failed) {
        long now = nanoClock.getAsLong();
        if (now - windowStart >= windowNanos) {
            windowStart = now;
            calls = 0;
            failures = 0;
        }
        calls++;
        if (failed) {
            failures++;
        }
        if (calls >= minimumCalls && failures >= failureRateThreshold * calls) {
            open();
        }
    }

    private void open() {
        state = State.OPEN;
        openedAt = nanoClock.getAsLong();
        timesOpened++;
    }

    private void close() {
        state = State.CLOSED;
        windowStart = nanoClock.getAsLong();
        calls = 0;
        failures = 0;
    }
}
