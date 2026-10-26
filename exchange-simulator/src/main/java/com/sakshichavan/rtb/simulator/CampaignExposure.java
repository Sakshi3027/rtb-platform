package com.sakshichavan.rtb.simulator;

/** How a capped campaign's winning bids were distributed across users during one run. */
public record CampaignExposure(String campaignId, int cap, long usersReached, long maxPerUser, long usersOverCap) {

    String format() {
        return "%s (cap %d): users %d | max per user %d | over cap %d"
                .formatted(campaignId, cap, usersReached, maxPerUser, usersOverCap);
    }
}
