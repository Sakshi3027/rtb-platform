package com.sakshichavan.rtb.bidder.ml;

import com.sakshichavan.rtb.common.audience.AudienceSegments;
import com.sakshichavan.rtb.common.features.ImpressionFeatures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Measures single-threaded CTR inference latency for realistic batch sizes (the eligible campaigns of one request).
 * Usage: java -cp bidder.jar com.sakshichavan.rtb.bidder.ml.CtrBenchmark
 */
public final class CtrBenchmark {

    private static final List<String> CAMPAIGNS = List.of("c-sports-us", "c-news-global", "c-tech-us-gb",
            "c-food-anywhere", "c-travel-eu", "c-auto-na", "c-finance-us", "c-mobile-network", "c-brand-awareness",
            "c-games-india");
    private static final List<String> SIZES = List.of("300x250", "728x90", "320x50", "160x600", "300x600");
    private static final List<String> COUNTRIES = List.of("USA", "GBR", "DEU", "CAN", "IND", "BRA");
    private static final List<String> CATEGORIES = List.of("IAB12", "IAB17", "IAB19", "IAB8", "IAB20", "IAB2", "IAB13", "IAB9");

    private static final int WARMUP_CALLS = 20_000;
    private static final int MEASURED_CALLS = 100_000;

    private CtrBenchmark() {
    }

    private static List<ImpressionFeatures> sample(SplittableRandom random, int count) {
        List<ImpressionFeatures> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            List<String> segments = AudienceSegments.ALL.stream().filter(s -> random.nextDouble() < 0.2).toList();
            rows.add(new ImpressionFeatures(
                    CAMPAIGNS.get(random.nextInt(CAMPAIGNS.size())),
                    SIZES.get(random.nextInt(SIZES.size())),
                    COUNTRIES.get(random.nextInt(COUNTRIES.size())),
                    CATEGORIES.get(random.nextInt(CATEGORIES.size())),
                    segments, random.nextDouble() < 0.8));
        }
        return rows;
    }

    private static double micros(long nanos) {
        return nanos / 1_000.0;
    }

    public static void main(String[] args) {
        SplittableRandom random = new SplittableRandom(1);
        try (OnnxCtrModel model = OnnxCtrModel.fromClasspath("/models/ctr-v1.onnx")) {
            System.out.printf("model %s, single thread, %,d measured calls per batch size%n",
                    model.version(), MEASURED_CALLS);
            System.out.printf("%-8s%10s%10s%10s%10s%16s%n", "batch", "p50 us", "p99 us", "p99.9 us", "max us",
                    "predictions/s");
            for (int batch : new int[] {1, 3, 10}) {
                List<List<ImpressionFeatures>> inputs = new ArrayList<>();
                for (int i = 0; i < 1_000; i++) {
                    inputs.add(sample(random, batch));
                }
                for (int i = 0; i < WARMUP_CALLS; i++) {
                    model.predict(inputs.get(i % inputs.size()));
                }
                long[] nanos = new long[MEASURED_CALLS];
                long started = System.nanoTime();
                for (int i = 0; i < MEASURED_CALLS; i++) {
                    long t0 = System.nanoTime();
                    model.predict(inputs.get(i % inputs.size()));
                    nanos[i] = System.nanoTime() - t0;
                }
                double seconds = (System.nanoTime() - started) / 1e9;
                Arrays.sort(nanos);
                System.out.printf("%-8d%10.1f%10.1f%10.1f%10.1f%,16.0f%n", batch,
                        micros(nanos[MEASURED_CALLS / 2]),
                        micros(nanos[(int) (MEASURED_CALLS * 0.99)]),
                        micros(nanos[(int) (MEASURED_CALLS * 0.999)]),
                        micros(nanos[MEASURED_CALLS - 1]),
                        MEASURED_CALLS * batch / seconds);
            }
        }
    }
}
