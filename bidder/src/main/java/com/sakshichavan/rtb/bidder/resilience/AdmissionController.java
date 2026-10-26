package com.sakshichavan.rtb.bidder.resilience;

import com.sakshichavan.rtb.bidder.AsyncBidEngine;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Load shedding at the front door. Bid work runs on a bounded worker pool instead of the I/O event loops,
 * so the event loops stay responsive and rejecting a request costs almost nothing.
 *
 * Two ways a request is shed, each answered immediately with a no-bid (HTTP 204):
 *   queue full: no room to wait, rejected on arrival.
 *   stale:      it waited longer than maxQueueDelay before a worker reached it, so it would likely miss the
 *               exchange deadline anyway; the worker drops it without doing the work.
 * Under overload this keeps latency bounded for accepted requests, instead of every request getting slower
 * until none meet the deadline (congestion collapse).
 */
public final class AdmissionController implements AsyncBidEngine, AutoCloseable {

    private final AsyncBidEngine delegate;
    private final ThreadPoolExecutor workers;
    private final long maxQueueDelayNanos;

    private final LongAdder admitted = new LongAdder();
    private final LongAdder shedQueueFull = new LongAdder();
    private final LongAdder shedStale = new LongAdder();

    public AdmissionController(AsyncBidEngine delegate, int workerThreads, int queueCapacity, Duration maxQueueDelay) {
        if (workerThreads <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("worker threads and queue capacity must be positive");
        }
        this.delegate = delegate;
        this.maxQueueDelayNanos = maxQueueDelay.toNanos();
        AtomicInteger threadNumber = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(
                workerThreads, workerThreads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "bid-worker-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public CompletionStage<Optional<BidResponse>> evaluate(BidRequest request) {
        long enqueuedAt = System.nanoTime();
        CompletableFuture<Optional<BidResponse>> result = new CompletableFuture<>();
        try {
            workers.execute(() -> process(request, enqueuedAt, result));
        } catch (RejectedExecutionException e) {
            shedQueueFull.increment();
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return result;
    }

    private void process(BidRequest request, long enqueuedAt, CompletableFuture<Optional<BidResponse>> result) {
        if (System.nanoTime() - enqueuedAt > maxQueueDelayNanos) {
            shedStale.increment();
            result.complete(Optional.empty());
            return;
        }
        admitted.increment();
        try {
            delegate.evaluate(request).whenComplete((bid, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                } else {
                    result.complete(bid);
                }
            });
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
    }

    public AdmissionStats stats() {
        return new AdmissionStats(admitted.sum(), shedQueueFull.sum(), shedStale.sum(), workers.getQueue().size());
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
