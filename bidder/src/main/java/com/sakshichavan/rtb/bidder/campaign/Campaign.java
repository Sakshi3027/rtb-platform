package com.sakshichavan.rtb.bidder.campaign;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Objects;

/**
 * An advertiser campaign: creative, max CPM, daily budget in dollars, targeting,
 * and an optional per-user frequency cap (null means uncapped).
 */
public record Campaign(
        String id,
        String creativeId,
        double maxBidCpm,
        double dailyBudgetUsd,
        Targeting targeting,
        FrequencyCap frequencyCap) {

    @JsonCreator
    public Campaign {
        Objects.requireNonNull(id, "campaign.id is required");
        Objects.requireNonNull(creativeId, "campaign.creativeId is required");
        if (maxBidCpm <= 0) {
            throw new IllegalArgumentException("maxBidCpm must be positive for campaign " + id);
        }
        if (dailyBudgetUsd <= 0) {
            throw new IllegalArgumentException("dailyBudgetUsd must be positive for campaign " + id);
        }
        if (targeting == null) {
            targeting = Targeting.ANY;
        }
    }

    /** Convenience constructor for an uncapped campaign. */
    public Campaign(String id, String creativeId, double maxBidCpm, double dailyBudgetUsd, Targeting targeting) {
        this(id, creativeId, maxBidCpm, dailyBudgetUsd, targeting, null);
    }
}
