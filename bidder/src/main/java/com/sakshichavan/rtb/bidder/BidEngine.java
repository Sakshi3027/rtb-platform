package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;

import java.util.Optional;

/**
 * Decides whether and how much to bid. Returns empty for a no-bid.
 * Implementations run on a Netty event loop thread, so they must be fast and must never block.
 */
@FunctionalInterface
public interface BidEngine {

    Optional<BidResponse> evaluate(BidRequest request);
}
