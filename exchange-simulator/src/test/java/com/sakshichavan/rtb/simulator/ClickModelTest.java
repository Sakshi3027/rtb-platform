package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.features.ImpressionFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickModelTest {

    private static ImpressionFeatures sportsAd(String category, List<String> segments) {
        return new ImpressionFeatures("c-sports-us", "300x250", "USA", category, segments, true);
    }

    @Test
    void matchingContextAndUserAddTheirEffectsPlusAnInteraction() {
        double none = ClickModel.logit(sportsAd("IAB12", List.of()));
        double context = ClickModel.logit(sportsAd("IAB17", List.of()));
        double user = ClickModel.logit(sportsAd("IAB12", List.of("sports-fan")));
        double both = ClickModel.logit(sportsAd("IAB17", List.of("sports-fan")));

        assertEquals(ClickModel.CONTEXT_MATCH, context - none, 1e-12);
        assertEquals(ClickModel.USER_MATCH, user - none, 1e-12);
        assertEquals(ClickModel.CONTEXT_MATCH + ClickModel.USER_MATCH + ClickModel.BOTH_MATCH, both - none, 1e-12);
    }

    @Test
    void probabilitiesAreRealisticForDisplayAds() {
        double unmatched = ClickModel.probability(sportsAd("IAB12", List.of()));
        double fullMatch = ClickModel.probability(sportsAd("IAB17", List.of("sports-fan")));

        assertTrue(unmatched > 0.005 && unmatched < 0.01, "unmatched CTR was " + unmatched);
        assertTrue(fullMatch > 0.05 && fullMatch < 0.08, "full-match CTR was " + fullMatch);
    }

    @Test
    void knowsEveryBundledCampaign() {
        assertEquals(10, ClickModel.campaignIds().size());
        assertTrue(ClickModel.campaignIds().contains("c-sports-us"));
    }
}
