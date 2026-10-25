package com.sakshichavan.rtb.common.openrtb;

import java.util.List;
import java.util.Objects;

/**
 * A bid request from an exchange. tmax is the time budget in milliseconds:
 * the bidder must respond within it or the bid is dropped.
 */
public record BidRequest(
        String id,
        List<Imp> imp,
        Site site,
        Device device,
        User user,
        int tmax,
        AuctionType at) {

    public BidRequest {
        Objects.requireNonNull(id, "request.id is required");
        if (imp == null || imp.isEmpty()) {
            throw new IllegalArgumentException("request must contain at least one imp");
        }
        imp = List.copyOf(imp);
        if (tmax <= 0) {
            throw new IllegalArgumentException("tmax must be positive: " + tmax);
        }
        if (at == null) {
            at = AuctionType.SECOND_PRICE;
        }
    }
}
