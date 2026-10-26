package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.budget.SpendGuard;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.common.money.Micros;
import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;
import com.sakshichavan.rtb.common.openrtb.Imp;
import com.sakshichavan.rtb.common.openrtb.SeatBid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * For each impression: find matching campaigns that clear the floor, try them from highest value down,
 * price the bid for the auction type, and bid with the first campaign whose budget can cover
 * the impression's cost. Reserving at bid time means spend can never exceed budget.
 */
public final class TargetingBidEngine implements BidEngine {

    static final String SEAT = "rtb-platform";

    private static final Comparator<Campaign> HIGHEST_VALUE_FIRST =
            Comparator.comparingDouble(Campaign::maxBidCpm).reversed();

    private final TargetingIndex index;
    private final BidPricer pricer;
    private final SpendGuard spendGuard;

    public TargetingBidEngine(TargetingIndex index, BidPricer pricer, SpendGuard spendGuard) {
        this.index = index;
        this.pricer = pricer;
        this.spendGuard = spendGuard;
    }

    @Override
    public Optional<BidResponse> evaluate(BidRequest request) {
        List<Bid> bids = new ArrayList<>(request.imp().size());

        for (Imp imp : request.imp()) {
            List<Campaign> eligible = new ArrayList<>();
            for (Campaign campaign : index.match(request, imp)) {
                if (campaign.maxBidCpm() >= imp.bidFloor()) {
                    eligible.add(campaign);
                }
            }
            eligible.sort(HIGHEST_VALUE_FIRST);

            for (Campaign campaign : eligible) {
                double price = pricer.price(campaign.maxBidCpm(), imp.bidFloor(), request.at());
                long costMicros = Math.max(1, Micros.impressionCost(price));
                if (spendGuard.tryReserve(campaign.id(), costMicros)) {
                    bids.add(new Bid(request.id() + "-" + imp.id(), imp.id(), price,
                            campaign.id(), campaign.creativeId(), null));
                    break;
                }
            }
        }

        if (bids.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BidResponse(request.id(), List.of(new SeatBid(SEAT, bids)), "USD"));
    }
}
