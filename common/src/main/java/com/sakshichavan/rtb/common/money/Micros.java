package com.sakshichavan.rtb.common.money;

/**
 * Money as long micros: 1 dollar = 1,000,000 micros. Integer arithmetic is exact,
 * so budgets and spend never accumulate floating-point error.
 */
public final class Micros {

    public static final long PER_DOLLAR = 1_000_000L;
    private static final double IMPRESSIONS_PER_CPM = 1_000.0;

    private Micros() {
    }

    public static long fromDollars(double dollars) {
        return Math.round(dollars * PER_DOLLAR);
    }

    public static double toDollars(long micros) {
        return micros / (double) PER_DOLLAR;
    }

    /** Cost of ONE impression at a CPM price. CPM is the price per thousand impressions. */
    public static long impressionCost(double cpmDollars) {
        return Math.round(cpmDollars * PER_DOLLAR / IMPRESSIONS_PER_CPM);
    }
}
