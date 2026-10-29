package com.sakshichavan.rtb.bidder.budget;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.sakshichavan.rtb.bidder.NoticeSink;
import com.sakshichavan.rtb.common.money.Micros;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Settles budget reservations when the exchange reports the auction outcome.
 *   win:       charge the clearing price (never more than reserved) and release the rest
 *   loss:      release the whole reservation
 *   no notice: after ttl, assume a loss and release the whole reservation
 *
 * Exactly-once: settling starts with an atomic remove of the pending reservation, so only the first notice
 * for a bid can settle it; retried or duplicate notices find nothing and change nothing.
 */
public final class ReservationLedger implements NoticeSink {

    private record Reservation(String campaignId, long micros) {
    }

    private final CampaignBudgets budgets;
    private final String noticeBaseUrl;
    private final Cache<String, Reservation> pending;

    private final LongAdder wins = new LongAdder();
    private final LongAdder losses = new LongAdder();
    private final LongAdder expired = new LongAdder();
    private final LongAdder unmatched = new LongAdder();
    private final LongAdder spentMicros = new LongAdder();
    private final LongAdder releasedMicros = new LongAdder();

    public ReservationLedger(CampaignBudgets budgets, String noticeBaseUrl, Duration ttl, LongSupplier nanoClock) {
        this.budgets = budgets;
        this.noticeBaseUrl = noticeBaseUrl;
        this.pending = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(2_000_000)
                .ticker(nanoClock::getAsLong)
                .scheduler(Scheduler.systemScheduler())
                .executor(Runnable::run)
                .<String, Reservation>removalListener((bidId, reservation, cause) -> {
                    if (reservation != null && cause.wasEvicted()) {
                        expired.increment();
                        release(reservation, reservation.micros());
                    }
                })
                .build();
    }

    public void record(String bidId, String campaignId, long micros) {
        pending.put(bidId, new Reservation(campaignId, micros));
    }

    public String winUrl(String bidId) {
        return noticeBaseUrl + "/win?bid=" + encode(bidId) + "&price=${AUCTION_PRICE}";
    }

    public String lossUrl(String bidId) {
        return noticeBaseUrl + "/loss?bid=" + encode(bidId);
    }

    @Override
    public void win(String bidId, double clearingPriceCpm) {
        Reservation reservation = pending.asMap().remove(bidId);
        if (reservation == null) {
            unmatched.increment();
            return;
        }
        long cost = Math.min(reservation.micros(), Math.max(1, Micros.impressionCost(clearingPriceCpm)));
        wins.increment();
        spentMicros.add(cost);
        release(reservation, reservation.micros() - cost);
    }

    @Override
    public void loss(String bidId) {
        Reservation reservation = pending.asMap().remove(bidId);
        if (reservation == null) {
            unmatched.increment();
            return;
        }
        losses.increment();
        release(reservation, reservation.micros());
    }

    /** Runs pending expirations now. Expiry also happens automatically during normal use. */
    public void cleanUp() {
        pending.cleanUp();
    }

    public NoticeStats stats() {
        return new NoticeStats(pending.estimatedSize(), wins.sum(), losses.sum(), expired.sum(), unmatched.sum(),
                spentMicros.sum(), releasedMicros.sum());
    }

    private void release(Reservation reservation, long micros) {
        if (micros > 0) {
            budgets.release(reservation.campaignId(), micros);
            releasedMicros.add(micros);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
