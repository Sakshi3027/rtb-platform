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
import java.util.concurrent.atomic.LongAdder;

/**
 * Values an impression by its predicted click probability: value = maxBidCpm x predicted CTR / reference CTR,
 * where maxBidCpm is what an average impression is worth and the reference CTR is the average the model was trained
 * on. The value is capped at maxMultiplier x maxBidCpm so an extreme prediction cannot produce an extreme bid.
 *
 * All uncached campaigns of a request are predicted in one batched model call. Predictions are cached by their exact
 * features: the model is deterministic and every feature is categorical, so the feature space is small and repeats.
 * If the model fails or returns invalid output, this request falls back to fixed prices; bidding never stops.
 */
public final class CtrValuer implements CampaignValuer {

    private final CtrModel model;
    private final double referenceCtr;
    private final double maxMultiplier;
    private final Cache<ImpressionFeatures, Double> cache;

    private final LongAdder lookups = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder modelCalls = new LongAdder();
    private final LongAdder fallbacks = new LongAdder();

    public CtrValuer(CtrModel model, double referenceCtr, double maxMultiplier, long cacheSize) {
        if (!(referenceCtr > 0 && referenceCtr < 1)) {
            throw new IllegalArgumentException("reference CTR must be in (0, 1): " + referenceCtr);
        }
        if (!(maxMultiplier >= 1)) {
            throw new IllegalArgumentException("max multiplier must be at least 1: " + maxMultiplier);
        }
        this.model = model;
        this.referenceCtr = referenceCtr;
        this.maxMultiplier = maxMultiplier;
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
            double multiplier = Math.min(maxMultiplier, ctr[i] / referenceCtr);
            values[i] = campaigns.get(i).maxBidCpm() * multiplier;
        }
        return values;
    }

    private double[] fallback(BidRequest request, Imp imp, UserProfile profile, List<Campaign> campaigns) {
        fallbacks.increment();
        return CampaignValuer.FIXED.valuesCpm(request, imp, profile, campaigns);
    }

    public ValuerStats stats() {
        return new ValuerStats(lookups.sum(), cacheHits.sum(), modelCalls.sum(), fallbacks.sum());
    }
}
