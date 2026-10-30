package com.sakshichavan.rtb.bidder.ml;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sakshichavan.rtb.bidder.CampaignValuer;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.profile.UserProfile;
import com.sakshichavan.rtb.common.features.ImpressionFeatures;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Imp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Values an impression by its predicted click probability: value = maxBidCpm x predicted CTR / reference CTR,
 * capped at maxMultiplier x maxBidCpm so an extreme prediction cannot produce an extreme bid.
 *
 * GLOBAL normalization uses one reference CTR, the average of the training data. But training data assigns
 * campaigns at random, while a campaign only bids on the traffic its targeting selects, which is usually more
 * relevant than average, so GLOBAL raises a targeted campaign's prices across the board.
 * PER_CAMPAIGN normalization compares each impression with that campaign's own average predicted CTR on the traffic
 * it actually sees, so its multipliers average about 1: spend stays similar, and moves toward the campaign's most
 * clickable impressions. Until a campaign has minSamples valuations, the global reference is used.
 *
 * All uncached campaigns of a request are predicted in one batched model call. Predictions are cached by their exact
 * features: the model is deterministic and every feature is categorical, so the feature space is small and repeats.
 * If the model fails or returns invalid output, this request falls back to fixed prices; bidding never stops.
 */
public final class CtrValuer implements CampaignValuer {

    public enum Normalization {
        GLOBAL,
        PER_CAMPAIGN
    }

    /** A campaign's running average of predicted CTR: thread-safe sum and count. */
    private static final class Baseline {
        final DoubleAdder sum = new DoubleAdder();
        final LongAdder count = new LongAdder();
    }

    private final CtrModel model;
    private final double referenceCtr;
    private final double maxMultiplier;
    private final Normalization normalization;
    private final long minSamples;
    private final Cache<ImpressionFeatures, Double> cache;
    private final Map<String, Baseline> baselines = new ConcurrentHashMap<>();

    private final LongAdder lookups = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder modelCalls = new LongAdder();
    private final LongAdder fallbacks = new LongAdder();

    public CtrValuer(CtrModel model, double referenceCtr, double maxMultiplier, long cacheSize) {
        this(model, referenceCtr, maxMultiplier, cacheSize, Normalization.GLOBAL, 0);
    }

    public CtrValuer(CtrModel model, double referenceCtr, double maxMultiplier, long cacheSize,
                     Normalization normalization, long minSamples) {
        if (!(referenceCtr > 0 && referenceCtr < 1)) {
            throw new IllegalArgumentException("reference CTR must be in (0, 1): " + referenceCtr);
        }
        if (!(maxMultiplier >= 1)) {
            throw new IllegalArgumentException("max multiplier must be at least 1: " + maxMultiplier);
        }
        this.model = model;
        this.referenceCtr = referenceCtr;
        this.maxMultiplier = maxMultiplier;
        this.normalization = normalization;
        this.minSamples = minSamples;
        this.cache = Caffeine.newBuilder().maximumSize(cacheSize).build();
    }

    @Override
    public double[] valuesCpm(BidRequest request, Imp imp, UserProfile profile, List<Campaign> campaigns) {
        int n = campaigns.size();
        lookups.add(n);
        double[] ctr = new double[n];
        List<ImpressionFeatures> misses = new ArrayList<>(n);
        int[] missIndex = new int[n];

        for (int i = 0; i < n; i++) {
            ImpressionFeatures features = ImpressionFeatures.of(request, imp, campaigns.get(i).id(), profile.segments());
            Double cached = cache.getIfPresent(features);
            if (cached != null) {
                ctr[i] = cached;
                cacheHits.increment();
            } else {
                missIndex[misses.size()] = i;
                misses.add(features);
            }
        }

        if (!misses.isEmpty()) {
            double[] predicted;
            try {
                predicted = model.predict(misses);
                modelCalls.increment();
            } catch (RuntimeException e) {
                return fallback(request, imp, profile, campaigns);
            }
            if (predicted.length != misses.size()) {
                return fallback(request, imp, profile, campaigns);
            }
            for (int j = 0; j < misses.size(); j++) {
                if (!(predicted[j] >= 0 && predicted[j] <= 1)) {
                    return fallback(request, imp, profile, campaigns);
                }
                ctr[missIndex[j]] = predicted[j];
                cache.put(misses.get(j), predicted[j]);
            }
        }

        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            Campaign campaign = campaigns.get(i);
            double reference = referenceFor(campaign.id(), ctr[i]);
            double multiplier = Math.min(maxMultiplier, ctr[i] / reference);
            values[i] = campaign.maxBidCpm() * multiplier;
        }
        return values;
    }

    /** The CTR an average impression has for this campaign; also records this prediction in its running average. */
    private double referenceFor(String campaignId, double predictedCtr) {
        if (normalization == Normalization.GLOBAL) {
            return referenceCtr;
        }
        Baseline baseline = baselines.computeIfAbsent(campaignId, id -> new Baseline());
        long count = baseline.count.sum();
        double average = (count > 0) ? baseline.sum.sum() / count : 0;
        baseline.sum.add(predictedCtr);
        baseline.count.increment();
        return (count >= minSamples && average > 0) ? average : referenceCtr;
    }

    private double[] fallback(BidRequest request, Imp imp, UserProfile profile, List<Campaign> campaigns) {
        fallbacks.increment();
        return CampaignValuer.FIXED.valuesCpm(request, imp, profile, campaigns);
    }

    public ValuerStats stats() {
        return new ValuerStats(lookups.sum(), cacheHits.sum(), modelCalls.sum(), fallbacks.sum());
    }

    /** Each campaign's average predicted CTR on the traffic it has seen (PER_CAMPAIGN only), sorted by campaign. */
    public Map<String, Double> baselines() {
        Map<String, Double> snapshot = new TreeMap<>();
        baselines.forEach((campaignId, baseline) -> {
            long count = baseline.count.sum();
            if (count > 0) {
                snapshot.put(campaignId, baseline.sum.sum() / count);
            }
        });
        return snapshot;
    }
}
