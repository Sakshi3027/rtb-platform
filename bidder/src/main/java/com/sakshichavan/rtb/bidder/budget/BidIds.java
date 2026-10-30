package com.sakshichavan.rtb.bidder.budget;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates globally unique bid ids: b-<random 64-bit process id>-<counter>.
 * Exchange request ids are only unique within one exchange's traffic (and a deterministic simulator repeats them
 * every run), so they must never be the bidder's identity: settlement events derived from colliding ids get
 * deduplicated as if they were retries, silently dropping real spend.
 * The counter makes ids unique within a process; the random prefix makes them unique across processes and restarts.
 */
public final class BidIds {

    private final String prefix;
    private final AtomicLong sequence = new AtomicLong();

    BidIds(long processId) {
        this.prefix = "b-" + Long.toHexString(processId) + "-";
    }

    /** A generator with a fresh random process id, drawn once from a cryptographically strong source. */
    public static BidIds random() {
        return new BidIds(new SecureRandom().nextLong());
    }

    public String next() {
        return prefix + Long.toHexString(sequence.incrementAndGet());
    }
}
