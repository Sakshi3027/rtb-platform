package com.sakshichavan.rtb.bidder;

/**
 * Benchmark-only: adds a fixed CPU cost to every bid, standing in for heavier real work such as
 * model inference, so overload behavior can be reproduced on a laptop. Disabled (0) by default.
 */
final class SimulatedWork {

    private SimulatedWork() {
    }

    static AsyncBidEngine wrap(AsyncBidEngine engine, long micros) {
        long nanos = micros * 1_000;
        return request -> {
            spin(nanos);
            return engine.evaluate(request);
        };
    }

    /** Busy-waits so the cost is real CPU time, unlike sleep, which would free the thread. */
    static void spin(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() - end < 0) {
            Thread.onSpinWait();
        }
    }
}
