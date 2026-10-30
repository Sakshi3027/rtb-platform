package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.profile.UserProfile;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Imp;

import java.util.List;

/** What one impression is worth to each campaign, as a CPM, in the same order as the campaigns. */
@FunctionalInterface
public interface CampaignValuer {

    double[] valuesCpm(BidRequest request, Imp imp, UserProfile profile, List<Campaign> campaigns);

    /** Every impression is worth the campaign's fixed price, the original behavior. */
    CampaignValuer FIXED = (request, imp, profile, campaigns) ->
            campaigns.stream().mapToDouble(Campaign::maxBidCpm).toArray();
}
