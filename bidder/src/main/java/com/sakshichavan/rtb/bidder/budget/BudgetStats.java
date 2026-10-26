package com.sakshichavan.rtb.bidder.budget;

/**
 * One campaign's budget accounting on this bidder instance, in micros.
 * granted: leased from the shared source. reserved: committed by bids. leaseRemaining: granted but unused.
 * When the bidder is idle, granted == reserved + leaseRemaining.
 */
public record BudgetStats(long grantedMicros, long reservedMicros, long leaseRemainingMicros) {
}
