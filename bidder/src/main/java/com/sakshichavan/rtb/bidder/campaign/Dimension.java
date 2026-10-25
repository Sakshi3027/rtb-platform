package com.sakshichavan.rtb.bidder.campaign;

import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Inverted index for one targeting dimension (size, country, or category).
 * Maps each value to the set of campaign indices that target it, stored as a BitSet.
 * Campaigns that do not restrict this dimension live in the "any" set.
 * Only mutated while the owning TargetingIndex is being built; read-only afterwards.
 */
final class Dimension<K> {

    private final Map<K, BitSet> byValue = new HashMap<>();
    private final BitSet any = new BitSet();

    void add(int campaignIndex, Set<K> targetedValues) {
        if (targetedValues.isEmpty()) {
            any.set(campaignIndex);
            return;
        }
        for (K value : targetedValues) {
            byValue.computeIfAbsent(value, key -> new BitSet()).set(campaignIndex);
        }
    }

    /** Campaigns compatible with the request's values: "any" campaigns plus those targeting any of the values. */
    BitSet candidates(Collection<K> requestValues) {
        BitSet result = (BitSet) any.clone();
        for (K value : requestValues) {
            BitSet targeting = byValue.get(value);
            if (targeting != null) {
                result.or(targeting);
            }
        }
        return result;
    }
}
