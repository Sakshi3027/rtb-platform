package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.profile.ProfileStore;
import com.sakshichavan.rtb.bidder.profile.UserProfile;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * The async shell around the pure engine: look up the user's profile within a strict time budget,
 * then run the engine. A slow, failed, or missing profile never fails the bid: the pipeline falls back
 * to UNKNOWN and bids with campaigns that need no audience data. A late profile is worthless,
 * because the exchange will not wait past tmax.
 */
public final class BidPipeline implements AsyncBidEngine {

    private final ProfileStore profiles;
    private final TargetingBidEngine engine;
    private final long profileTimeoutMillis;

    public BidPipeline(ProfileStore profiles, TargetingBidEngine engine, long profileTimeoutMillis) {
        if (profileTimeoutMillis <= 0) {
            throw new IllegalArgumentException("profile timeout must be positive: " + profileTimeoutMillis);
        }
        this.profiles = profiles;
        this.engine = engine;
        this.profileTimeoutMillis = profileTimeoutMillis;
    }

    @Override
    public CompletionStage<Optional<BidResponse>> evaluate(BidRequest request) {
        return profileFor(request).thenApply(profile -> engine.evaluate(request, profile));
    }

    private CompletableFuture<UserProfile> profileFor(BidRequest request) {
        if (request.user() == null || request.user().id() == null) {
            return CompletableFuture.completedFuture(UserProfile.UNKNOWN);
        }
        CompletableFuture<UserProfile> lookup;
        try {
            lookup = profiles.lookup(request.user().id()).toCompletableFuture();
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(UserProfile.UNKNOWN);
        }
        return lookup
                .exceptionally(error -> UserProfile.UNKNOWN)
                .completeOnTimeout(UserProfile.UNKNOWN, profileTimeoutMillis, TimeUnit.MILLISECONDS);
    }
}
