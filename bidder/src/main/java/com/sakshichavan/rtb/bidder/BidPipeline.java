package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.profile.ProfileStats;
import com.sakshichavan.rtb.bidder.profile.ProfileStore;
import com.sakshichavan.rtb.bidder.profile.UserProfile;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * The async shell around the pure engine: look up the user's profile within a strict time budget,
 * then run the engine. A slow, failed, or missing profile never fails the bid: the pipeline falls back
 * to UNKNOWN and bids with campaigns that need no audience data.
 *
 * Whichever finishes first wins: the lookup or the deadline timer. The timer is cancelled when the
 * lookup wins, and removed from the timer queue, so fast lookups leave no pending tasks behind.
 */
public final class BidPipeline implements AsyncBidEngine, AutoCloseable {

    private final ProfileStore profiles;
    private final TargetingBidEngine engine;
    private final long profileTimeoutMillis;
    private final ScheduledThreadPoolExecutor timer;

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder errors = new LongAdder();

    public BidPipeline(ProfileStore profiles, TargetingBidEngine engine, long profileTimeoutMillis) {
        if (profileTimeoutMillis <= 0) {
            throw new IllegalArgumentException("profile timeout must be positive: " + profileTimeoutMillis);
        }
        this.profiles = profiles;
        this.engine = engine;
        this.profileTimeoutMillis = profileTimeoutMillis;
        this.timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "profile-deadline");
            thread.setDaemon(true);
            return thread;
        });
        this.timer.setRemoveOnCancelPolicy(true);
    }

    @Override
    public CompletionStage<Optional<BidResponse>> evaluate(BidRequest request) {
        return profileFor(request).thenApply(profile -> engine.evaluate(request, profile));
    }

    public ProfileStats stats() {
        return new ProfileStats(hits.sum(), misses.sum(), timeouts.sum(), errors.sum());
    }

    private CompletableFuture<UserProfile> profileFor(BidRequest request) {
        if (request.user() == null || request.user().id() == null) {
            return CompletableFuture.completedFuture(UserProfile.UNKNOWN);
        }

        CompletableFuture<UserProfile> result = new CompletableFuture<>();
        ScheduledFuture<?> deadline = timer.schedule(() -> {
            if (result.complete(UserProfile.UNKNOWN)) {
                timeouts.increment();
            }
        }, profileTimeoutMillis, TimeUnit.MILLISECONDS);
        result.whenComplete((profile, error) -> deadline.cancel(false));

        CompletionStage<UserProfile> lookup;
        try {
            lookup = profiles.lookup(request.user().id());
        } catch (RuntimeException e) {
            lookup = CompletableFuture.failedFuture(e);
        }
        lookup.whenComplete((profile, error) -> {
            if (error != null || profile == null) {
                if (result.complete(UserProfile.UNKNOWN)) {
                    errors.increment();
                }
            } else if (result.complete(profile)) {
                (profile.segments().isEmpty() ? misses : hits).increment();
            }
        });
        return result;
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
