package com.sakshichavan.rtb.simulator;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Ground truth for frequency caps, measured from outside the bidders: counts how many bids each user
 * received from each campaign. Thread-safe; recorded from many response callbacks at once.
 */
final class FrequencyAudit {

    private record Exposure(String campaignId, String userId) {
    }

    private final Map<Exposure, LongAdder> counts = new ConcurrentHashMap<>();

    void record(String userId, String campaignId) {
        counts.computeIfAbsent(new Exposure(campaignId, userId), key -> new LongAdder()).increment();
    }

    /** Summarizes only the campaigns listed in caps, sorted by campaign id. */
    List<CampaignExposure> summarize(Map<String, Integer> caps) {
        Map<String, Tally> tallies = new TreeMap<>();
        counts.forEach((exposure, count) -> {
            Integer cap = caps.get(exposure.campaignId());
            if (cap == null) {
                return;
            }
            long received = count.sum();
            Tally tally = tallies.computeIfAbsent(exposure.campaignId(), id -> new Tally());
            tally.users++;
            tally.max = Math.max(tally.max, received);
            if (received > cap) {
                tally.overCap++;
            }
        });
        return tallies.entrySet().stream()
                .map(e -> new CampaignExposure(e.getKey(), caps.get(e.getKey()),
                        e.getValue().users, e.getValue().max, e.getValue().overCap))
                .toList();
    }

    private static final class Tally {
        long users;
        long max;
        long overCap;
    }
}
