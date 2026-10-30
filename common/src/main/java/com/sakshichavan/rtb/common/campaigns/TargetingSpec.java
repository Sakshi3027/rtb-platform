package com.sakshichavan.rtb.common.campaigns;

import java.util.List;

/** Who a campaign may bid on. An empty list means "any". */
public record TargetingSpec(List<SizeSpec> sizes, List<String> countries, List<String> categories,
                            List<String> segments) {

    public static final TargetingSpec ANY = new TargetingSpec(null, null, null, null);

    public TargetingSpec {
        sizes = (sizes == null) ? List.of() : List.copyOf(sizes);
        countries = (countries == null) ? List.of() : List.copyOf(countries);
        categories = (categories == null) ? List.of() : List.copyOf(categories);
        segments = (segments == null) ? List.of() : List.copyOf(segments);
    }
}
