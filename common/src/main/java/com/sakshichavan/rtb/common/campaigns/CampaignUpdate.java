package com.sakshichavan.rtb.common.campaigns;

/**
 * One message on the compacted campaigns topic: a campaign's full state at a version.
 * Consumers apply it only if its version is newer than the one they hold, so redelivered copies are harmless.
 * revision is the change's global position in the campaign service's change log.
 */
public record CampaignUpdate(long revision, long version, CampaignSpec campaign) {
}
