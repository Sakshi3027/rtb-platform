package com.sakshichavan.rtb.bidder.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(0);
    private final CircuitBreaker breaker =
            new CircuitBreaker(0.5, 10, Duration.ofSeconds(1), Duration.ofSeconds(2), now::get);

    private void advanceSeconds(long seconds) {
        now.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            breaker.onFailure();
        }
    }

    private void succeed(int times) {
        for (int i = 0; i < times; i++) {
            breaker.onSuccess();
        }
    }

    @Test
    void staysClosedBelowTheMinimumNumberOfCalls() {
        fail(9);

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.tryAcquire());
    }

    @Test
    void opensWhenTheFailureRateReachesTheThreshold() {
        succeed(5);
        fail(5);

        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquire());
        assertEquals(1, breaker.timesOpened());
    }

    @Test
    void staysClosedWhenFailuresAreBelowTheThreshold() {
        succeed(6);
        fail(4);

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void oldFailuresExpireWithTheWindow() {
        fail(8);
        advanceSeconds(2);
        fail(8);

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void allowsExactlyOneProbeAfterTheOpenDuration() {
        fail(10);
        assertFalse(breaker.tryAcquire());

        advanceSeconds(2);

        assertTrue(breaker.tryAcquire());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertFalse(breaker.tryAcquire());
    }

    @Test
    void successfulProbeClosesTheBreaker() {
        fail(10);
        advanceSeconds(2);
        breaker.tryAcquire();

        breaker.onSuccess();

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.tryAcquire());
    }

    @Test
    void failedProbeReopensTheBreaker() {
        fail(10);
        advanceSeconds(2);
        breaker.tryAcquire();

        breaker.onFailure();

        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquire());
        assertEquals(2, breaker.timesOpened());
    }
}
