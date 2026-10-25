package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.common.openrtb.AuctionType;

/**
 * Turns a campaign's value into a bid price for a given auction type.
 *
 * Second price: bid true value. The winner pays the runner-up's price, so shading only loses wins.
 * First price:  shade toward the floor, b = F + alpha * (v - F). If the strongest competing bid is
 *               uniform on [F, v], expected surplus (v - b)(b - F) is maximized at alpha = 0.5.
 *               Phase 5 replaces the uniform assumption with win/loss data.
 */
public final class BidPricer {

    private static final double ROUNDING_EPSILON = 1e-9;

    private final double shadingFactor;

    public BidPricer(double shadingFactor) {
        if (shadingFactor <= 0 || shadingFactor > 1) {
            throw new IllegalArgumentException("shading factor must be in (0, 1]: " + shadingFactor);
        }
        this.shadingFactor = shadingFactor;
    }

    public double price(double value, double floor, AuctionType auctionType) {
        if (auctionType == AuctionType.SECOND_PRICE) {
            return value;
        }
        double shaded = floor + shadingFactor * (value - floor);
        double roundedUpToCent = Math.ceil(shaded * 100 - ROUNDING_EPSILON) / 100.0;
        return Math.min(value, Math.max(floor, roundedUpToCent));
    }
}
