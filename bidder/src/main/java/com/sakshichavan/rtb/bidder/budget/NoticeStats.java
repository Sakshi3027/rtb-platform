package com.sakshichavan.rtb.bidder.budget;

/**
 * pending: bids awaiting a notice. unmatched: notices with no pending reservation (duplicates, late, or unknown).
 * spentMicros: charged at clearing prices. releasedMicros: returned to budgets by wins, losses, and expiries.
 */
public record NoticeStats(long pending, long wins, long losses, long expired, long unmatched,
                          long spentMicros, long releasedMicros) {
}
