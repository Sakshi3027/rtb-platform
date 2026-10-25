package com.sakshichavan.rtb.common.openrtb;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Response to a BidRequest. A no-bid is NOT represented here: per OpenRTB,
 * the bidder returns HTTP 204 with no body, so a BidResponse always has bids.
 */
public record BidResponse(String id, List<SeatBid> seatBid, String cur) {

    public BidResponse {
        Objects.requireNonNull(id, "response.id is required");
        if (seatBid == null || seatBid.isEmpty()) {
            throw new IllegalArgumentException("response must contain at least one seatbid");
        }
        seatBid = List.copyOf(seatBid);
        if (cur == null) {
            cur = "USD";
        }
    }

    /** Convenience factory for the common case: one seat, one bid. */
    public static BidResponse single(String requestId, String seat, Bid bid) {
        return new BidResponse(requestId, List.of(new SeatBid(seat, List.of(bid))), "USD");
    }

    /**
     * Checks this response the way an exchange would: same request id,
     * every bid targets a real impression, and every price meets that impression's floor.
     */
    public boolean isValidFor(BidRequest request) {
        if (!id.equals(request.id())) {
            return false;
        }
        Map<String, Imp> impsById = request.imp().stream()
                .collect(Collectors.toMap(Imp::id, Function.identity(), (first, second) -> first));

        return seatBid.stream()
                .flatMap(seat -> seat.bid().stream())
                .allMatch(bid -> {
                    Imp imp = impsById.get(bid.impId());
                    return imp != null && bid.price() >= imp.bidFloor();
                });
    }
}
