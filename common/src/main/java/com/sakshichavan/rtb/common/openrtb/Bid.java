package com.sakshichavan.rtb.common.openrtb;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * A bid for one impression. price is CPM in the response currency.
 * nurl is the win notice URL the exchange calls if this bid wins.
 */
public record Bid(
        String id,
        @JsonProperty("impid") String impId,
        double price,
        @JsonProperty("adid") String adId,
        @JsonProperty("crid") String crId,
        String nurl) {

    public Bid {
        Objects.requireNonNull(id, "bid.id is required");
        Objects.requireNonNull(impId, "bid.impId is required");
        if (price <= 0) {
            throw new IllegalArgumentException("bid price must be positive: " + price);
        }
    }
}
