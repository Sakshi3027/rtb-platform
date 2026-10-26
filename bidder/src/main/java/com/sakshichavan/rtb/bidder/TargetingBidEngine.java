package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.budget.SpendGuard;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.bidder.frequency.FrequencyGuard;
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
 * For each impression: find matching campaigns that clear the floor and try them from highest value down.
 * A campaign bids only if the user is under its frequency cap AND its budget covers the impression cost.
 * If the budget check fails after a frequency slot was taken, the slot is released.
 */
public final class TargetingBidEngine implements BidEngine {

    static final String SEAT = "rtb-platform";

    private static final Comparator<Campaign> HIGHEST_VALUE_FIRST =
            Comparator.comparingDouble(Campaign::maxBidCpm).reversed();

    private final TargetingIndex index;
    private final BidPricer pricer;
    private final SpendGuard spendGuard;
    private final FrequencyGuard frequencyGuard;

    public TargetingBidEngine(TargetingIndex index, BidPricer pricer, SpendGuard spendGuard,
                              FrequencyGuard frequencyGuard) {
        this.index = index;
        this.pricer = pricer;
        this.spendGuard = spendGuard;
        this.frequencyGuard = frequencyGuard;
    }

    @Override
    public Optional<BidResponse> evaluate(BidRequest request) {
        String userId = (request.user() == null) ? null : request.user().id();
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
                if (!frequencyGuard.tryAcquire(userId, campaign)) {
                    continue;
                }
                double price = pricer.price(campaign.maxBidCpm(), imp.bidFloor(), request.at());
                long costMicros = Math.max(1, Micros.impressionCost(price));
                if (spendGuard.tryReserve(campaign.id(), costMicros)) {
                    bids.add(new Bid(request.id() + "-" + imp.id(), imp.id(), price,
                            campaign.id(), campaign.creativeId(), null));
                    break;
                }
                frequencyGuard.release(userId, campaign);
            }
        }

        if (bids.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BidResponse(request.id(), List.of(new SeatBid(SEAT, bids)), "USD"));
    }
}
