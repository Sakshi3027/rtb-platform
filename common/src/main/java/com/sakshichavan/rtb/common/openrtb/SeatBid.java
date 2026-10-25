package com.sakshichavan.rtb.common.openrtb;

import java.util.List;

/** Bids grouped under one buyer seat. Must contain at least one bid. */
public record SeatBid(String seat, List<Bid> bid) {

    public SeatBid {
        if (bid == null || bid.isEmpty()) {
            throw new IllegalArgumentException("seatbid must contain at least one bid");
        }
        bid = List.copyOf(bid);
    }
}
