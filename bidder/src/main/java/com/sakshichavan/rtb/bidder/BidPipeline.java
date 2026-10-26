package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.profile.ProfileStats;
import com.sakshichavan.rtb.bidder.profile.ProfileStore;
import com.sakshichavan.rtb.bidder.profile.UserProfile;
import com.sakshichavan.rtb.bidder.resilience.CircuitBreaker;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

/**
 * The async shell around the pure engine: look up the user's profile within a strict time budget,
 * then run the engine. A slow, failed, or missing profile never fails the bid: the pipeline falls back
 * to UNKNOWN and bids with campaigns that need no audience data.
 *
 * Whichever finishes first wins: the lookup or the deadline timer. Only the winner records the outcome,
 * so each lookup is counted, and reported to the circuit breaker, exactly once. While the breaker is open,
 * lookups are skipped entirely and requests bid immediately without a profile.
 */
public final class BidPipeline implements AsyncBidEngine, AutoCloseable {

    private final ProfileStore profiles;
    private final TargetingBidEngine engine;
    private final long profileTimeoutMillis;
    private final CircuitBreaker breaker;
    private final ScheduledThreadPoolExecutor timer;

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder shortCircuited = new LongAdder();

    public BidPipeline(ProfileStore profiles, TargetingBidEngine engine, long profileTimeoutMillis) {
        this(profiles, engine, profileTimeoutMillis, CircuitBreaker.withDefaults(System::nanoTime));
    }

    public BidPipeline(ProfileStore profiles, TargetingBidEngine engine, long profileTimeoutMillis,
                       CircuitBreaker breaker) {
        if (profileTimeoutMillis <= 0) {
            throw new IllegalArgumentException("profile timeout must be positive: " + profileTimeoutMillis);
        }
        this.profiles = profiles;
        this.engine = engine;
        this.profileTimeoutMillis = profileTimeoutMillis;
        this.breaker = breaker;
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
        return new ProfileStats(hits.sum(), misses.sum(), timeouts.sum(), errors.sum(), shortCircuited.sum(),
                breaker.state().name(), breaker.timesOpened());
    }

    private CompletableFuture<UserProfile> profileFor(BidRequest request) {
        if (request.user() == null || request.user().id() == null) {
            return CompletableFuture.completedFuture(UserProfile.UNKNOWN);
        }
        if (!breaker.tryAcquire()) {
            shortCircuited.increment();
            return CompletableFuture.completedFuture(UserProfile.UNKNOWN);
        }

        CompletableFuture<UserProfile> result = new CompletableFuture<>();
        ScheduledFuture<?> deadline = timer.schedule(() -> {
            if (result.complete(UserProfile.UNKNOWN)) {
                timeouts.increment();
                breaker.onFailure();
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
                    (isTimeout(error) ? timeouts : errors).increment();
                    breaker.onFailure();
                }
            } else if (result.complete(profile)) {
                (profile.segments().isEmpty() ? misses : hits).increment();
                breaker.onSuccess();
            }
        });
        return result;
    }

    private static boolean isTimeout(Throwable error) {
        Throwable cause = (error instanceof CompletionException && error.getCause() != null)
                ? error.getCause()
                : error;
        return cause instanceof TimeoutException;
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
