package com.sakshichavan.rtb.billing;

/** Ledger totals: every settlement recorded, how many were wins, and the total charged. */
public record BillingTotals(long settlements, long wins, long chargedMicros) {
}
