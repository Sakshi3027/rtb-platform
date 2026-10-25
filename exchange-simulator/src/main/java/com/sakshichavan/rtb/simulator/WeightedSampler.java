package com.sakshichavan.rtb.simulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Picks items at random according to fixed weights.
 * The cumulative distribution is built once; each sample is a binary search, O(log n).
 * Immutable and thread-safe: the caller supplies the random source.
 */
public final class WeightedSampler<T> {

    private final List<T> items;
    private final double[] cumulative;

    public WeightedSampler(List<T> items, double[] weights) {
        if (items.isEmpty() || items.size() != weights.length) {
            throw new IllegalArgumentException("need exactly one weight per item");
        }
        this.items = List.copyOf(items);
        this.cumulative = new double[weights.length];

        double total = 0;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] < 0) {
                throw new IllegalArgumentException("weights must be non-negative");
            }
            total += weights[i];
            cumulative[i] = total;
        }
        if (total <= 0) {
            throw new IllegalArgumentException("at least one weight must be positive");
        }
        for (int i = 0; i < cumulative.length; i++) {
            cumulative[i] /= total;
        }
    }

    public T sample(SplittableRandom random) {
        double x = random.nextDouble();
        int index = Arrays.binarySearch(cumulative, x);
        if (index < 0) {
            index = -index - 1;
        }
        return items.get(Math.min(index, items.size() - 1));
    }

    /** Zipf distribution over ranks 0..n-1: rank k gets weight 1 / (k + 1)^exponent. */
    public static WeightedSampler<Integer> zipf(int n, double exponent) {
        List<Integer> ranks = new ArrayList<>(n);
        double[] weights = new double[n];
        for (int k = 0; k < n; k++) {
            ranks.add(k);
            weights[k] = 1.0 / Math.pow(k + 1, exponent);
        }
        return new WeightedSampler<>(ranks, weights);
    }
}
