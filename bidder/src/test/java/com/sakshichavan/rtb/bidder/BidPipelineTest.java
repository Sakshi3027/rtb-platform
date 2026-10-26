package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.budget.SpendGuard;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.Targeting;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.bidder.frequency.FrequencyGuard;
import com.sakshichavan.rtb.bidder.profile.ProfileStore;
import com.sakshichavan.rtb.bidder.profile.UserProfile;
import com.sakshichavan.rtb.common.openrtb.AuctionType;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Imp;
import com.sakshichavan.rtb.common.openrtb.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BidPipelineTest {

    private static final Campaign AUTO_AUDIENCE = new Campaign("auto-audience", "cr-a", 3.00, 100.0,
            new Targeting(null, null, null, Set.of("auto-intender")));
    private static final Campaign BROAD = new Campaign("broad", "cr-b", 1.00, 100.0, null);

    private static BidRequest request(String userId) {
        Imp imp = new Imp("1", new Banner(300, 250), 0.10, "USD");
        User user = (userId == null) ? null : new User(userId);
        return new BidRequest("r1", List.of(imp), null, null, user, 100, AuctionType.SECOND_PRICE);
    }

    private static BidPipeline pipeline(ProfileStore store, long timeoutMillis) {
        TargetingBidEngine engine = new TargetingBidEngine(new TargetingIndex(List.of(AUTO_AUDIENCE, BROAD)),
                new BidPricer(0.5), SpendGuard.UNLIMITED, FrequencyGuard.UNLIMITED);
        return new BidPipeline(store, engine, timeoutMillis);
    }

    private static String winner(BidPipeline pipeline, BidRequest request) throws Exception {
        return pipeline.evaluate(request).toCompletableFuture().get(1, TimeUnit.SECONDS)
                .orElseThrow().seatBid().get(0).bid().get(0).adId();
    }

    @Test
    void usesProfileSegmentsForAudienceTargeting() throws Exception {
        ProfileStore store = userId -> CompletableFuture.completedFuture(new UserProfile(Set.of("auto-intender")));

        assertEquals("auto-audience", winner(pipeline(store, 5), request("u1")));
    }

    @Test
    void bidsWithoutAudienceDataWhenTheStoreIsTooSlow() throws Exception {
        ProfileStore neverAnswers = userId -> new CompletableFuture<>();
        long start = System.nanoTime();

        String winner = winner(pipeline(neverAnswers, 20), request("u1"));

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals("broad", winner);
        assertTrue(elapsedMillis < 500, "took " + elapsedMillis + "ms");
    }

    @Test
    void bidsWithoutAudienceDataWhenTheStoreFails() throws Exception {
        ProfileStore broken = userId -> CompletableFuture.failedFuture(new IllegalStateException("store is down"));

        assertEquals("broad", winner(pipeline(broken, 5), request("u1")));
    }

    @Test
    void skipsTheLookupForAnonymousUsers() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        ProfileStore counting = userId -> {
            lookups.incrementAndGet();
            return CompletableFuture.completedFuture(UserProfile.UNKNOWN);
        };

        assertEquals("broad", winner(pipeline(counting, 5), request(null)));
        assertEquals(0, lookups.get());
    }
}
