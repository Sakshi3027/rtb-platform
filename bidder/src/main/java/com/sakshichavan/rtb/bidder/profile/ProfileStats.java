package com.sakshichavan.rtb.bidder.profile;

/**
 * Outcomes of profile lookups on the bid path. Each identified request ends in exactly one of
 * hits, misses, timeouts, errors, or shortCircuited (skipped because the circuit breaker was open).
 */
public record ProfileStats(long hits, long misses, long timeouts, long errors, long shortCircuited,
                           String breakerState, long breakerOpens) {
}
