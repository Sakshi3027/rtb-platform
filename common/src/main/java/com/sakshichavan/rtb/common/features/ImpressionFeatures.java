package com.sakshichavan.rtb.common.features;

import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Imp;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The features a click model sees for one (campaign, impression, user) combination.
 * Extracted by the same code for training data and for serving, so the model sees identical inputs in both.
 * Missing values become "unknown" rather than null, so every feature always has a value.
 * Segments are sorted so the same set always produces the same list.
 */
public record ImpressionFeatures(
        String campaignId,
        String size,
        String country,
        String category,
        List<String> segments,
        boolean knownUser) {

    public static final String UNKNOWN = "unknown";

    public ImpressionFeatures {
        Objects.requireNonNull(campaignId, "campaignId is required");
        segments = List.copyOf(segments);
    }

    /** @param segments the user's audience segments; ignored for anonymous users */
    public static ImpressionFeatures of(BidRequest request, Imp imp, String campaignId, Collection<String> segments) {
        String size = (imp.banner() == null) ? UNKNOWN : imp.banner().w() + "x" + imp.banner().h();

        String country = (request.device() != null && request.device().geo() != null
                && request.device().geo().country() != null)
                ? request.device().geo().country()
                : UNKNOWN;

        String category = (request.site() != null && request.site().cat() != null && !request.site().cat().isEmpty())
                ? request.site().cat().get(0)
                : UNKNOWN;

        boolean knownUser = request.user() != null && request.user().id() != null;
        List<String> sortedSegments = knownUser ? segments.stream().sorted().toList() : List.of();

        return new ImpressionFeatures(campaignId, size, country, category, sortedSegments, knownUser);
    }
}
