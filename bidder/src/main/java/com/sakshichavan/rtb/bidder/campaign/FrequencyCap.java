package com.sakshichavan.rtb.bidder.campaign;

import java.util.concurrent.TimeUnit;

/** At most maxImpressions per user within any sliding window of windowSeconds. */
public record FrequencyCap(int maxImpressions, long windowSeconds) {

    public FrequencyCap {
        if (maxImpressions <= 0) {
            throw new IllegalArgumentException("maxImpressions must be positive: " + maxImpressions);
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive: " + windowSeconds);
        }
    }

    public long windowNanos() {
        return TimeUnit.SECONDS.toNanos(windowSeconds);
    }
}
