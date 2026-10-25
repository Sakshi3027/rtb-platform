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
 * For each impression, picks the highest-value matching campaign that clears the floor,
 * then prices the bid for the request's auction type (truthful for second price, shaded for first price).
 */
public final class TargetingBidEngine implements BidEngine {

    static final String SEAT = "rtb-platform";

    private final TargetingIndex index;
    private final BidPricer pricer;

    public TargetingBidEngine(TargetingIndex index, BidPricer pricer) {
        this.index = index;
        this.pricer = pricer;
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
                double price = pricer.price(best.maxBidCpm(), imp.bidFloor(), request.at());
                bids.add(new Bid(request.id() + "-" + imp.id(), imp.id(), price,
                        best.id(), best.creativeId(), null));
            }
        }

        if (bids.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BidResponse(request.id(), List.of(new SeatBid(SEAT, bids)), "USD"));
    }
}
