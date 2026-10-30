package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.audience.AudienceSegments;
import com.sakshichavan.rtb.common.features.ImpressionFeatures;
import com.sakshichavan.rtb.common.openrtb.AuctionType;
import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;
import com.sakshichavan.rtb.common.openrtb.Imp;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Plays the exchange's side of the auction against one simulated competitor per request.
 * The strongest competing bid is log-normal around the floor: floor * e^(mu + sigma * Z).
 * First price: the winner pays its own bid. Second price: the winner pays the competing bid (or floor) plus
 * one cent, capped at its own bid. Notices are imperfect on purpose: some losses are never reported, and some
 * notices are sent twice, to exercise the bidder's expiry and exactly-once settlement.
 * A won impression is clicked with its true probability from the hidden ClickModel.
 */
public final class AuctionHouse {

    /** Random choices for one request, drawn up front on the sending thread so runs are deterministic. */
    record Plan(double competingCpm, boolean sendLossNotice, boolean duplicateNotice, double clickDraw) {

        /** A plan whose impression is never clicked (a draw of 1.0 is never below a probability). */
        Plan(double competingCpm, boolean sendLossNotice, boolean duplicateNotice) {
            this(competingCpm, sendLossNotice, duplicateNotice, 1.0);
        }
    }

    /** The auction result, the notice URLs the exchange calls (duplicates included), and the click outcome. */
    record Outcome(boolean won, double clearingCpm, List<String> noticeUrls, int duplicates,
                   double trueCtr, boolean clicked) {
    }

    static final String PRICE_MACRO = "${AUCTION_PRICE}";

    private final SplittableRandom random;
    private final double mu;
    private final double sigma;
    private final double lossNoticeRate;
    private final double duplicateRate;

    public AuctionHouse(long seed, double mu, double sigma, double lossNoticeRate, double duplicateRate) {
        this.random = new SplittableRandom(seed);
        this.mu = mu;
        this.sigma = sigma;
        this.lossNoticeRate = lossNoticeRate;
        this.duplicateRate = duplicateRate;
    }

    /** Draws this request's competitor, notice behavior, and click draw. Not thread-safe: call from one thread. */
    Plan plan(BidRequest request) {
        double floor = Math.max(0.01, request.imp().get(0).bidFloor());
        double competing = floor * Math.exp(mu + sigma * random.nextGaussian());
        boolean sendLossNotice = random.nextDouble() < lossNoticeRate;
        boolean duplicateNotice = random.nextDouble() < duplicateRate;
        double clickDraw = random.nextDouble();
        return new Plan(competing, sendLossNotice, duplicateNotice, clickDraw);
    }

    /** Decides the auction for the response's first bid, and whether a won impression is clicked. Thread-safe. */
    static Outcome settle(Plan plan, BidRequest request, BidResponse response) {
        Bid bid = response.seatBid().get(0).bid().get(0);
        Imp imp = request.imp().get(0);
        int copies = plan.duplicateNotice() ? 2 : 1;

        boolean won = bid.price() > plan.competingCpm() && bid.price() >= imp.bidFloor();
        if (won) {
            double clearing = (request.at() == AuctionType.FIRST_PRICE)
                    ? bid.price()
                    : Math.min(bid.price(), Math.max(plan.competingCpm(), imp.bidFloor()) + 0.01);
            clearing = roundToTenThousandths(clearing);
            List<String> urls = (bid.nurl() == null)
                    ? List.of()
                    : Collections.nCopies(copies, substitutePrice(bid.nurl(), clearing));
            double trueCtr = ClickModel.probability(ImpressionFeatures.of(
                    request, imp, (bid.adId() == null) ? ImpressionFeatures.UNKNOWN : bid.adId(), segments(request)));
            boolean clicked = plan.clickDraw() < trueCtr;
            return new Outcome(true, clearing, urls, urls.isEmpty() ? 0 : copies - 1, trueCtr, clicked);
        }

        List<String> urls = (plan.sendLossNotice() && bid.lurl() != null)
                ? Collections.nCopies(copies, bid.lurl())
                : List.of();
        return new Outcome(false, 0, urls, urls.isEmpty() ? 0 : copies - 1, 0, false);
    }

    private static Set<String> segments(BidRequest request) {
        return (request.user() == null || request.user().id() == null)
                ? Set.of()
                : AudienceSegments.forUser(request.user().id());
    }

    /** Locale.ROOT guarantees a '.' decimal separator; some locales would write "0,8000". */
    static String substitutePrice(String nurl, double clearingCpm) {
        return nurl.replace(PRICE_MACRO, String.format(Locale.ROOT, "%.4f", clearingCpm));
    }

    static double roundToTenThousandths(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }
}
