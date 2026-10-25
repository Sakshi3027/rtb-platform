package com.sakshichavan.rtb.bidder.campaign;

import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Imp;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Finds campaigns matching a request using bitmap intersection across targeting dimensions.
 * Immutable after construction: safe to share across all event loop threads with no locking.
 */
public final class TargetingIndex {

    private final List<Campaign> campaigns;
    private final Dimension<com.sakshichavan.rtb.common.openrtb.Banner> sizes = new Dimension<>();
    private final Dimension<String> countries = new Dimension<>();
    private final Dimension<String> categories = new Dimension<>();

    public TargetingIndex(List<Campaign> campaigns) {
        this.campaigns = List.copyOf(campaigns);
        for (int i = 0; i < this.campaigns.size(); i++) {
            Targeting targeting = this.campaigns.get(i).targeting();
            sizes.add(i, targeting.sizes());
            countries.add(i, targeting.countries());
            categories.add(i, targeting.categories());
        }
    }

    public List<Campaign> match(BidRequest request, Imp imp) {
        BitSet matches = sizes.candidates(List.of(imp.banner()));
        if (matches.isEmpty()) {
            return List.of();
        }
        matches.and(countries.candidates(countryOf(request)));
        if (matches.isEmpty()) {
            return List.of();
        }
        matches.and(categories.candidates(categoriesOf(request)));

        List<Campaign> result = new ArrayList<>(matches.cardinality());
        for (int i = matches.nextSetBit(0); i >= 0; i = matches.nextSetBit(i + 1)) {
            result.add(campaigns.get(i));
        }
        return result;
    }

    public int size() {
        return campaigns.size();
    }

    private static List<String> countryOf(BidRequest request) {
        if (request.device() == null || request.device().geo() == null || request.device().geo().country() == null) {
            return List.of();
        }
        return List.of(request.device().geo().country());
    }

    private static List<String> categoriesOf(BidRequest request) {
        return (request.site() == null) ? List.of() : request.site().cat();
    }
}
