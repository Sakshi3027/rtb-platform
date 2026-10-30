package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.features.ImpressionFeatures;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

/**
 * The hidden ground truth: the true probability that a user clicks a given ad in a given context.
 * The bidder never sees this; it has to learn it from logged clicks.
 * Every effect is additive on the log-odds scale, except that matching BOTH the site's context and the user's
 * interests adds an extra boost: an interaction a plain linear model cannot represent without a cross feature.
 */
public final class ClickModel {

    /** What a campaign is about: the user segment and site category it is relevant to (null means none). */
    record Theme(String segment, String category) {
    }

    static final double BASE_LOGIT = -4.9;
    static final double CONTEXT_MATCH = 0.7;
    static final double USER_MATCH = 1.1;
    static final double BOTH_MATCH = 0.6;
    static final double ANONYMOUS = -0.3;

    private static final Theme NO_THEME = new Theme(null, null);

    private static final Map<String, Theme> THEMES = Map.ofEntries(
            entry("c-sports-us", new Theme("sports-fan", "IAB17")),
            entry("c-news-global", new Theme(null, "IAB12")),
            entry("c-tech-us-gb", new Theme("gamer", "IAB19")),
            entry("c-food-anywhere", new Theme("foodie", "IAB8")),
            entry("c-travel-eu", new Theme("traveler", "IAB20")),
            entry("c-auto-na", new Theme("auto-intender", "IAB2")),
            entry("c-finance-us", new Theme("finance-pro", "IAB13")),
            entry("c-mobile-network", NO_THEME),
            entry("c-brand-awareness", NO_THEME),
            entry("c-games-india", new Theme("gamer", "IAB9")));

    private static final Map<String, Double> SIZE_EFFECT = Map.of(
            "300x250", 0.0, "728x90", -0.3, "320x50", -0.6, "160x600", -0.2, "300x600", 0.3);

    private static final Map<String, Double> COUNTRY_EFFECT = Map.of(
            "USA", 0.0, "GBR", 0.1, "DEU", -0.1, "CAN", 0.05, "IND", 0.3, "BRA", 0.2);

    private static final Map<String, Double> CREATIVE_QUALITY = Map.of(
            "c-brand-awareness", 0.2, "c-finance-us", 0.1, "c-mobile-network", -0.2);

    private ClickModel() {
    }

    /** The campaigns the world knows about, in a stable order. */
    static List<String> campaignIds() {
        return THEMES.keySet().stream().sorted().toList();
    }

    static double logit(ImpressionFeatures features) {
        Theme theme = THEMES.getOrDefault(features.campaignId(), NO_THEME);
        boolean contextMatch = theme.category() != null && theme.category().equals(features.category());
        boolean userMatch = theme.segment() != null && features.segments().contains(theme.segment());

        double logit = BASE_LOGIT
                + SIZE_EFFECT.getOrDefault(features.size(), 0.0)
                + COUNTRY_EFFECT.getOrDefault(features.country(), 0.0)
                + CREATIVE_QUALITY.getOrDefault(features.campaignId(), 0.0);
        if (contextMatch) {
            logit += CONTEXT_MATCH;
        }
        if (userMatch) {
            logit += USER_MATCH;
        }
        if (contextMatch && userMatch) {
            logit += BOTH_MATCH;
        }
        if (!features.knownUser()) {
            logit += ANONYMOUS;
        }
        return logit;
    }

    /** The true click probability: the logistic (sigmoid) function of the log-odds. */
    static double probability(ImpressionFeatures features) {
        return 1.0 / (1.0 + Math.exp(-logit(features)));
    }
}
