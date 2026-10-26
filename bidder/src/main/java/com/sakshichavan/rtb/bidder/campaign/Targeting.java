package com.sakshichavan.rtb.bidder.campaign;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.sakshichavan.rtb.common.openrtb.Banner;

import java.util.Set;

/**
 * Who a campaign wants to reach. An empty set means "any value" for that dimension.
 * segments are audience segments from the user's profile (e.g. "auto-intender").
 */
public record Targeting(Set<Banner> sizes, Set<String> countries, Set<String> categories, Set<String> segments) {

    public static final Targeting ANY = new Targeting(Set.of(), Set.of(), Set.of(), Set.of());

    @JsonCreator
    public Targeting {
        sizes = (sizes == null) ? Set.of() : Set.copyOf(sizes);
        countries = (countries == null) ? Set.of() : Set.copyOf(countries);
        categories = (categories == null) ? Set.of() : Set.copyOf(categories);
        segments = (segments == null) ? Set.of() : Set.copyOf(segments);
    }

    /** Targeting with no audience segment requirement. */
    public Targeting(Set<Banner> sizes, Set<String> countries, Set<String> categories) {
        this(sizes, countries, categories, Set.of());
    }
}
