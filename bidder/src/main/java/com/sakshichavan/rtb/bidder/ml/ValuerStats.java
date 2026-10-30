package com.sakshichavan.rtb.bidder.ml;

/** lookups: campaign valuations requested. cacheHits: answered from the cache. modelCalls: batched inferences. */
public record ValuerStats(long lookups, long cacheHits, long modelCalls, long fallbacks) {
}
