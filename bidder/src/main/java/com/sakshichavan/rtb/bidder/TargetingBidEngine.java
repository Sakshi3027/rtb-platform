package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;
import com.sakshichavan.rtb.common.openrtb.Imp;
import com.sakshichavan.rtb.common.openrtb.SeatBid;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * For each impression, picks the highest-paying matching campaign whose max bid clears the floor.
 * Pricing is deliberately naive (bids its full max); Step 3 replaces it with real auction pricing.
 */
public final class TargetingBidEngine implements BidEngine {

    static final String SEAT = "rtb-platform";

    private final TargetingIndex index;

    public TargetingBidEngine(TargetingIndex index) {
        this.index = index;
    }

    @Override
    public Optional<BidResponse> evaluate(BidRequest request) {
        List<Bid> bids = new ArrayList<>(request.imp().size());

        for (Imp imp : request.imp()) {
            Campaign best = null;
            for (Campaign campaign : index.match(request, imp)) {
                boolean clearsFloor = campaign.maxBidCpm() >= imp.bidFloor();
                if (clearsFloor && (best == null || campaign.maxBidCpm() > best.maxBidCpm())) {
                    best = campaign;
                }
            }
            if (best != null) {
                bids.add(new Bid(request.id() + "-" + imp.id(), imp.id(), best.maxBidCpm(),
                        best.id(), best.creativeId(), null));
            }
        }

        if (bids.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BidResponse(request.id(), List.of(new SeatBid(SEAT, bids)), "USD"));
    }
}
