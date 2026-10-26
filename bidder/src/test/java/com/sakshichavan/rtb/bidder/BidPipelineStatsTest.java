package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.budget.SpendGuard;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.bidder.frequency.FrequencyGuard;
import com.sakshichavan.rtb.bidder.profile.ProfileStats;
import com.sakshichavan.rtb.bidder.profile.ProfileStore;
import com.sakshichavan.rtb.bidder.profile.UserProfile;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Imp;
import com.sakshichavan.rtb.common.openrtb.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BidPipelineStatsTest {

    private static BidRequest request(String userId) {
        Imp imp = new Imp("1", new Banner(300, 250), 0.10, "USD");
        return new BidRequest("r-" + userId, List.of(imp), null, null, new User(userId), 100, null);
    }

    @Test
    void countsEachLookupOutcomeExactlyOnce() throws Exception {
        ProfileStore store = userId -> switch (userId) {
            case "hit" -> CompletableFuture.completedFuture(new UserProfile(Set.of("gamer")));
            case "miss" -> CompletableFuture.completedFuture(UserProfile.UNKNOWN);
            case "error" -> CompletableFuture.<UserProfile>failedFuture(new IllegalStateException("down"));
            case "store-timeout" -> CompletableFuture.<UserProfile>failedFuture(new TimeoutException("store timer"));
            default -> new CompletableFuture<UserProfile>();
        };
        TargetingBidEngine engine = new TargetingBidEngine(
                new TargetingIndex(List.of(new Campaign("broad", "cr-1", 1.0, 100.0, null))),
                new BidPricer(0.5), SpendGuard.UNLIMITED, FrequencyGuard.UNLIMITED);

        try (BidPipeline pipeline = new BidPipeline(store, engine, 20)) {
            for (String user : List.of("hit", "miss", "error", "store-timeout", "slow")) {
                pipeline.evaluate(request(user)).toCompletableFuture().get(1, TimeUnit.SECONDS);
            }

            assertEquals(new ProfileStats(1, 1, 2, 1, 0, "CLOSED", 0), pipeline.stats());
        }
    }
}
