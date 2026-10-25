package com.sakshichavan.rtb.simulator;

import org.HdrHistogram.Histogram;

/** Percentiles from one latency histogram, in microseconds. */
public record LatencySummary(long p50Micros, long p90Micros, long p99Micros, long p999Micros, long maxMicros) {

    static LatencySummary from(Histogram histogram) {
        return new LatencySummary(
                histogram.getValueAtPercentile(50.0),
                histogram.getValueAtPercentile(90.0),
                histogram.getValueAtPercentile(99.0),
                histogram.getValueAtPercentile(99.9),
                histogram.getMaxValue());
    }

    String format() {
        return "p50 %.2f | p90 %.2f | p99 %.2f | p99.9 %.2f | max %.2f".formatted(
                p50Micros / 1000.0, p90Micros / 1000.0, p99Micros / 1000.0,
                p999Micros / 1000.0, maxMicros / 1000.0);
    }
}
