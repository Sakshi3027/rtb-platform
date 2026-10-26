package com.sakshichavan.rtb.bidder.frequency;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.FrequencyCap;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Exact sliding-window frequency caps, held in memory per bidder instance.
 * Correct across instances when the load balancer routes each user to one bidder (user affinity).
 * Memory is bounded: Caffeine keeps at most maxUsers histories and evicts idle ones.
 * Each user's history has its own lock, so threads only contend on the same user at the same instant.
 */
public final class FrequencyCapper implements FrequencyGuard {

    private final Cache<String, UserHistory> histories;
    private final LongSupplier nanoClock;

    public FrequencyCapper(long maxUsers, Duration retention, LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.histories = Caffeine.newBuilder()
                .maximumSize(maxUsers)
                .expireAfterAccess(retention)
                .ticker(nanoClock::getAsLong)
                .build();
    }

    @Override
    public boolean tryAcquire(String userId, Campaign campaign) {
        FrequencyCap cap = campaign.frequencyCap();
        if (cap == null) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        UserHistory history = histories.get(userId, id -> new UserHistory());
        return history.tryAcquire(campaign.id(), cap, nanoClock.getAsLong());
    }

    @Override
    public void release(String userId, Campaign campaign) {
        if (campaign.frequencyCap() == null || userId == null) {
            return;
        }
        UserHistory history = histories.getIfPresent(userId);
        if (history != null) {
            history.release(campaign.id());
        }
    }

    /** Recent impression times per campaign for one user. At most maxImpressions timestamps per campaign. */
    private static final class UserHistory {
        private final Map<String, ArrayDeque<Long>> impressions = new HashMap<>(4);

        synchronized boolean tryAcquire(String campaignId, FrequencyCap cap, long nowNanos) {
            ArrayDeque<Long> times = impressions.computeIfAbsent(
                    campaignId, id -> new ArrayDeque<>(cap.maxImpressions()));
            long windowStart = nowNanos - cap.windowNanos();
            while (!times.isEmpty() && times.peekFirst() - windowStart <= 0) {
                times.pollFirst();
            }
            if (times.size() >= cap.maxImpressions()) {
                return false;
            }
            times.addLast(nowNanos);
            return true;
        }

        synchronized void release(String campaignId) {
            ArrayDeque<Long> times = impressions.get(campaignId);
            if (times != null) {
                times.pollLast();
            }
        }
    }
}
