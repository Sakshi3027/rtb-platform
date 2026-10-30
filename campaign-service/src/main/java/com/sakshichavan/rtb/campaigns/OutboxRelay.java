package com.sakshichavan.rtb.campaigns;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * The transactional outbox relay: reads committed changes after its stored position, publishes them, waits for
 * confirmation, and only then advances the position. A crash between publishing and recording republishes those
 * changes on restart (at-least-once); consumers ignore versions they already have, so the result is effectively once.
 */
public final class OutboxRelay implements AutoCloseable {

    public static final String PUBLISHER = "kafka";

    private final CampaignStore store;
    private final ChangePublisher publisher;
    private final int batchSize;
    private final Duration interval;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().name("outbox-relay").daemon().unstarted(runnable));
    private final LongAdder failures = new LongAdder();

    public OutboxRelay(CampaignStore store, ChangePublisher publisher, int batchSize, Duration interval) {
        this.store = store;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.interval = interval;
    }

    /** Publishes the next batch of unpublished changes; returns how many were published. */
    public int runOnce() throws Exception {
        long position = store.publishedRevision(PUBLISHER);
        List<CampaignChange> changes = store.changesAfter(position, batchSize);
        if (changes.isEmpty()) {
            return 0;
        }
        publisher.publish(changes);
        store.markPublished(PUBLISHER, changes.get(changes.size() - 1).revision());
        return changes.size();
    }

    /** Polls in the background. A failed attempt is counted and retried on the next tick, from the same position. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::drain, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void drain() {
        try {
            while (runOnce() == batchSize) {
                // a full batch means more may be waiting: keep going instead of sleeping
            }
        } catch (Exception e) {
            failures.increment();
        }
    }

    public long failures() {
        return failures.sum();
    }

    @Override
    public void close() throws InterruptedException {
        scheduler.shutdownNow();
        scheduler.awaitTermination(5, TimeUnit.SECONDS);
        publisher.close();
    }
}
