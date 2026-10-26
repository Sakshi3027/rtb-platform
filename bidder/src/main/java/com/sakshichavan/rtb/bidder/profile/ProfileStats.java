package com.sakshichavan.rtb.bidder.profile;

/** Outcomes of profile lookups on the bid path. A lookup ends in exactly one of these. */
public record ProfileStats(long hits, long misses, long timeouts, long errors) {
}
