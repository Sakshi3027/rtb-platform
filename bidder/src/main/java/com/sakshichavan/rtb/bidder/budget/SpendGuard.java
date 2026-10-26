package com.sakshichavan.rtb.bidder.budget;

/**
 * Decides whether a campaign may spend. Implementations must be fast and non-blocking.
 */
@FunctionalInterface
public interface SpendGuard {

    /** Reserves the amount from the campaign's budget; false means the campaign cannot spend it right now. */
    boolean tryReserve(String campaignId, long micros);

    /** Allows all spend. For tests and tools that do not exercise budgets. */
    SpendGuard UNLIMITED = (campaignId, micros) -> true;
}
