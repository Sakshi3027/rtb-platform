package com.sakshichavan.rtb.bidder.campaign;

import com.sakshichavan.rtb.common.openrtb.Banner;

import java.util.Set;

/**
 * Who a campaign wants to reach. An empty set means "any value" for that dimension.
 */
public record Targeting(Set<Banner> sizes, Set<String> countries, Set<String> categories) {

    public static final Targeting ANY = new Targeting(Set.of(), Set.of(), Set.of());

    public Targeting {
        sizes = (sizes == null) ? Set.of() : Set.copyOf(sizes);
        countries = (countries == null) ? Set.of() : Set.copyOf(countries);
        categories = (categories == null) ? Set.of() : Set.copyOf(categories);
    }
}
