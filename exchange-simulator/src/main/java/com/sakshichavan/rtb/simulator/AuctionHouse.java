package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.openrtb.AuctionType;
import com.sakshichavan.rtb.common.openrtb.Bid;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;
import com.sakshichavan.rtb.common.openrtb.Imp;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Plays the exchange's side of the auction against one simulated competitor per request.
 * The strongest competing bid is log-normal around the floor: floor * e^(mu + sigma * Z).
 * First price: the winner pays its own bid. Second price: the winner pays the competing bid (or floor) plus
 * one cent, capped at its own bid. Notices are imperfect on purpose: some losses are never reported, and some
 * notices are sent twice, to exercise the bidder's expiry and exactly-once settlement.
 */
public final class AuctionHouse {

    /** Random choices for one request, drawn up front on the sending thread so runs are deterministic. */
    record Plan(double competingCpm, boolean sendLossNotice, boolean duplicateNotice) {
    }

    /** The auction result and the notice URLs the exchange calls, duplicates included. */
    record Outcome(boolean won, double clearingCpm, List<String> noticeUrls, int duplicates) {
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

    /** Draws this request's competitor and notice behavior. Not thread-safe: call from one thread. */
    Plan plan(BidRequest request) {
        double floor = Math.max(0.01, request.imp().get(0).bidFloor());
        double competing = floor * Math.exp(mu + sigma * random.nextGaussian());
        boolean sendLossNotice = random.nextDouble() < lossNoticeRate;
        boolean duplicateNotice = random.nextDouble() < duplicateRate;
        return new Plan(competing, sendLossNotice, duplicateNotice);
    }

    /** Decides the auction for the response's first bid. Pure and thread-safe. */
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
            return new Outcome(true, clearing, urls, urls.isEmpty() ? 0 : copies - 1);
        }

        List<String> urls = (plan.sendLossNotice() && bid.lurl() != null)
                ? Collections.nCopies(copies, bid.lurl())
                : List.of();
        return new Outcome(false, 0, urls, urls.isEmpty() ? 0 : copies - 1);
    }

    /** Locale.ROOT guarantees a '.' decimal separator; some locales would write "0,8000". */
    static String substitutePrice(String nurl, double clearingCpm) {
        return nurl.replace(PRICE_MACRO, String.format(Locale.ROOT, "%.4f", clearingCpm));
    }

    static double roundToTenThousandths(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }
}
