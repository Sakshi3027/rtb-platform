package com.sakshichavan.rtb.common.campaigns;

/** Only ACTIVE campaigns bid. ARCHIVED is a soft delete: the history is kept. */
public enum CampaignStatus {
    ACTIVE,
    PAUSED,
    ARCHIVED
}
