package com.sakshichavan.rtb.campaigns;

import com.sakshichavan.rtb.common.campaigns.CampaignSpec;

/** A campaign and its version, which increases by one on every change and is exposed as the HTTP ETag. */
public record VersionedCampaign(CampaignSpec campaign, long version) {
}
