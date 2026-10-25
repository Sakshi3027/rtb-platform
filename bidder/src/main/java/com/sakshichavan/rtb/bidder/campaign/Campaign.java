package com.sakshichavan.rtb.bidder.campaign;

import java.util.Objects;

/** An advertiser campaign: what to show (creative), how much it will pay at most, and who to target. */
public record Campaign(String id, String creativeId, double maxBidCpm, Targeting targeting) {

    public Campaign {
        Objects.requireNonNull(id, "campaign.id is required");
        Objects.requireNonNull(creativeId, "campaign.creativeId is required");
        if (maxBidCpm <= 0) {
            throw new IllegalArgumentException("maxBidCpm must be positive for campaign " + id);
        }
        if (targeting == null) {
            targeting = Targeting.ANY;
        }
    }
}
