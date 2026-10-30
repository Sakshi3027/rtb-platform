package com.sakshichavan.rtb.common.campaigns;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A campaign as managed by the campaign service: the contract between the control plane and the bidders.
 * Matches the format of campaigns.json, plus a status, so existing campaign files import unchanged.
 * problems() reports every rule a spec breaks, so an operator can fix them all in one round trip.
 */
public record CampaignSpec(
        String id,
        String creativeId,
        double maxBidCpm,
        double dailyBudgetUsd,
        TargetingSpec targeting,
        FrequencyCapSpec frequencyCap,
        CampaignStatus status) {

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{2,63}$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern CATEGORY = Pattern.compile("^IAB\\d+(-\\d+)?$");
    private static final Pattern SEGMENT = Pattern.compile("^[a-z0-9-]{1,64}$");
    private static final long MIN_CAP_WINDOW_SECONDS = 60;
    private static final long MAX_CAP_WINDOW_SECONDS = 7 * 24 * 3600;

    public CampaignSpec {
        targeting = (targeting == null) ? TargetingSpec.ANY : targeting;
        status = (status == null) ? CampaignStatus.ACTIVE : status;
    }

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (id == null || !ID.matcher(id).matches()) {
            problems.add("id must be 3 to 64 lowercase letters, digits, and hyphens");
        }
        if (creativeId == null || creativeId.isBlank()) {
            problems.add("creativeId is required");
        }
        if (!(maxBidCpm > 0 && maxBidCpm <= 100)) {
            problems.add("maxBidCpm must be greater than 0 and at most 100");
        }
        if (!(dailyBudgetUsd > 0 && dailyBudgetUsd <= 1_000_000)) {
            problems.add("dailyBudgetUsd must be greater than 0 and at most 1,000,000");
        }
        if (targeting.sizes().stream().anyMatch(size -> size.w() <= 0 || size.h() <= 0)) {
            problems.add("every size needs a positive width and height");
        }
        if (targeting.countries().stream().anyMatch(country -> !COUNTRY.matcher(country).matches())) {
            problems.add("countries must be 3-letter ISO codes such as USA");
        }
        if (targeting.categories().stream().anyMatch(category -> !CATEGORY.matcher(category).matches())) {
            problems.add("categories must be IAB codes such as IAB17");
        }
        if (targeting.segments().stream().anyMatch(segment -> !SEGMENT.matcher(segment).matches())) {
            problems.add("segments must be lowercase letters, digits, and hyphens");
        }
        if (frequencyCap != null) {
            if (frequencyCap.maxImpressions() < 1) {
                problems.add("frequencyCap.maxImpressions must be at least 1");
            }
            if (frequencyCap.windowSeconds() < MIN_CAP_WINDOW_SECONDS
                    || frequencyCap.windowSeconds() > MAX_CAP_WINDOW_SECONDS) {
                problems.add("frequencyCap.windowSeconds must be between 60 seconds and 7 days");
            }
        }
        return problems;
    }
}
