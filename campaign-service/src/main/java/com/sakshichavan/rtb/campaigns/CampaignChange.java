package com.sakshichavan.rtb.campaigns;

import com.sakshichavan.rtb.common.campaigns.CampaignSpec;

/** One recorded change: the campaign's full new state at that version, in global revision order. */
public record CampaignChange(long revision, String campaignId, long version, CampaignSpec campaign) {
}
