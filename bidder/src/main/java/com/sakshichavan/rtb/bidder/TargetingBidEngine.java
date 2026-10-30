package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.budget.ReservationLedger;
import com.sakshichavan.rtb.bidder.budget.SpendGuard;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.bidder.frequency.FrequencyGuard;
import com.sakshichavan.rtb.bidder.profile.UserProfile;
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
 * The pure, synchronous decision core: no I/O. For each impression, find matching campaigns (including
 * audience segments from the user's profile), value the impression for each one, keep those whose value clears
 * the floor, and try them from highest value down: bid with the first one under its frequency cap whose budget
 * covers the impression cost. Valuation is pluggable: fixed prices, or predicted click probability.
 * With a ReservationLedger, each bid's reservation is recorded for settlement and the bid carries
 * win and loss notice URLs for the exchange to call.
 */
public final class TargetingBidEngine implements BidEngine {

    static final String SEAT = "rtb-platform";

    private record Candidate(Campaign campaign, double valueCpm) {
    }

    private static final Comparator<Candidate> HIGHEST_VALUE_FIRST =
            Comparator.comparingDouble(Candidate::valueCpm).reversed();

    private final TargetingIndex index;
    private final BidPricer pricer;
    private final SpendGuard spendGuard;
    private final FrequencyGuard frequencyGuard;
    private final ReservationLedger ledger;
    private final CampaignValuer valuer;

    public TargetingBidEngine(TargetingIndex index, BidPricer pricer, SpendGuard spendGuard,
                              FrequencyGuard frequencyGuard) {
        this(index, pricer, spendGuard, frequencyGuard, null, CampaignValuer.FIXED);
    }

    /** @param ledger records reservations and builds notice URLs; null disables settlement */
    public TargetingBidEngine(TargetingIndex index, BidPricer pricer, SpendGuard spendGuard,
                              FrequencyGuard frequencyGuard, ReservationLedger ledger) {
        this(index, pricer, spendGuard, frequencyGuard, ledger, CampaignValuer.FIXED);
    }

    public TargetingBidEngine(TargetingIndex index, BidPricer pricer, SpendGuard spendGuard,
                              FrequencyGuard frequencyGuard, ReservationLedger ledger, CampaignValuer valuer) {
        this.index = index;
        this.pricer = pricer;
        this.spendGuard = spendGuard;
        this.frequencyGuard = frequencyGuard;
        this.ledger = ledger;
        this.valuer = valuer;
    }

    @Override
    public Optional<BidResponse> evaluate(BidRequest request) {
        return evaluate(request, UserProfile.UNKNOWN);
    }

    public Optional<BidResponse> evaluate(BidRequest request, UserProfile profile) {
        String userId = (request.user() == null) ? null : request.user().id();
        List<Bid> bids = new ArrayList<>(request.imp().size());

        for (Imp imp : request.imp()) {
            List<Campaign> matched = new ArrayList<>();
            for (Campaign campaign : index.match(request, imp, profile.segments())) {
                matched.add(campaign);
            }
            if (matched.isEmpty()) {
                continue;
            }

            double[] values = valuer.valuesCpm(request, imp, profile, matched);
            List<Candidate> eligible = new ArrayList<>(matched.size());
            for (int i = 0; i < matched.size(); i++) {
                if (values[i] > 0 && values[i] >= imp.bidFloor()) {
                    eligible.add(new Candidate(matched.get(i), values[i]));
                }
            }
            eligible.sort(HIGHEST_VALUE_FIRST);

            for (Candidate candidate : eligible) {
                Campaign campaign = candidate.campaign();
                if (!frequencyGuard.tryAcquire(userId, campaign)) {
                    continue;
                }
                double price = pricer.price(candidate.valueCpm(), imp.bidFloor(), request.at());
                long costMicros = Math.max(1, Micros.impressionCost(price));
                if (spendGuard.tryReserve(campaign.id(), costMicros)) {
                    String bidId = (ledger != null) ? ledger.nextBidId() : request.id() + "-" + imp.id();
                    String nurl = null;
                    String lurl = null;
                    if (ledger != null) {
                        ledger.record(bidId, campaign.id(), costMicros);
                        nurl = ledger.winUrl(bidId);
                        lurl = ledger.lossUrl(bidId);
                    }
                    bids.add(new Bid(bidId, imp.id(), price, campaign.id(), campaign.creativeId(), nurl, lurl));
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
