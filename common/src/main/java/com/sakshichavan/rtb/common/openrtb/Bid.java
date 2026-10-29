package com.sakshichavan.rtb.common.openrtb;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * A bid for one impression. price is CPM in the response currency.
 * nurl is the win notice URL and lurl the loss notice URL; the exchange calls one of them after the auction.
 */
public record Bid(
        String id,
        @JsonProperty("impid") String impId,
        double price,
        @JsonProperty("adid") String adId,
        @JsonProperty("crid") String crId,
        String nurl,
        String lurl) {

    @JsonCreator
    public Bid {
        Objects.requireNonNull(id, "bid.id is required");
        Objects.requireNonNull(impId, "bid.impId is required");
        if (price <= 0) {
            throw new IllegalArgumentException("bid price must be positive: " + price);
        }
    }

    /** A bid without a loss notice URL. */
    public Bid(String id, String impId, double price, String adId, String crId, String nurl) {
        this(id, impId, price, adId, crId, nurl, null);
    }
}
