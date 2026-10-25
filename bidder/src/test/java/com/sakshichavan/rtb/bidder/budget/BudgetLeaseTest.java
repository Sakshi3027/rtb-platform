package com.sakshichavan.rtb.bidder.budget;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetLeaseTest {

    @Test
    void reservesWithinBalance() {
        BudgetLease lease = new BudgetLease(5_000);

        assertTrue(lease.tryReserve(2_000));
        assertEquals(3_000, lease.remainingMicros());
    }

    @Test
    void rejectsReservationLargerThanBalanceWithoutChangingIt() {
        BudgetLease lease = new BudgetLease(1_500);

        assertFalse(lease.tryReserve(2_000));
        assertEquals(1_500, lease.remainingMicros());
    }

    @Test
    void releaseReturnsBudget() {
        BudgetLease lease = new BudgetLease(5_000);
        lease.tryReserve(2_000);

        lease.release(2_000);

        assertEquals(5_000, lease.remainingMicros());
    }

    @Test
    void onlyOneCallerCanRefillAtATime() {
        BudgetLease lease = new BudgetLease(0);

        assertTrue(lease.tryBeginRefill());
        assertFalse(lease.tryBeginRefill());

        lease.completeRefill(10_000);

        assertEquals(10_000, lease.remainingMicros());
        assertTrue(lease.tryBeginRefill());
    }

    @Test
    void rejectsNonPositiveAmounts() {
        BudgetLease lease = new BudgetLease(5_000);

        assertThrows(IllegalArgumentException.class, () -> lease.tryReserve(0));
        assertThrows(IllegalArgumentException.class, () -> lease.release(-1));
    }

    @Test
    void neverOverspendsUnderHeavyContention() throws Exception {
        long initialMicros = 10_000_000;
        long costMicros = 1_000;
        int threads = 16;
        BudgetLease lease = new BudgetLease(initialMicros);
        LongAdder successfulReservations = new LongAdder();
        CountDownLatch startGate = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                workers.add(pool.submit(() -> {
                    startGate.await();
                    while (lease.tryReserve(costMicros)) {
                        successfulReservations.increment();
                    }
                    return null;
                }));
            }

            startGate.countDown();
            for (Future<?> worker : workers) {
                worker.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(initialMicros / costMicros, successfulReservations.sum());
        assertEquals(0, lease.remainingMicros());
    }
}
