package com.sakshichavan.rtb.simulator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedSamplerTest {

    @Test
    void rejectsMismatchedWeights() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedSampler<>(List.of("a", "b"), new double[] {1.0}));
    }

    @Test
    void samplesInProportionToWeights() {
        WeightedSampler<String> sampler = new WeightedSampler<>(List.of("a", "b"), new double[] {3, 1});
        SplittableRandom random = new SplittableRandom(7);

        int countA = 0;
        int total = 40_000;
        for (int i = 0; i < total; i++) {
            if (sampler.sample(random).equals("a")) {
                countA++;
            }
        }

        double shareA = (double) countA / total;
        assertTrue(shareA > 0.73 && shareA < 0.77, "share of a was " + shareA);
    }

    @Test
    void zipfFavorsLowRanks() {
        WeightedSampler<Integer> zipf = WeightedSampler.zipf(10, 1.0);
        SplittableRandom random = new SplittableRandom(7);

        int[] counts = new int[10];
        for (int i = 0; i < 50_000; i++) {
            counts[zipf.sample(random)]++;
        }

        assertTrue(counts[0] > counts[1], "rank 0 should beat rank 1");
        assertTrue(counts[1] > counts[9], "rank 1 should beat rank 9");
    }
}
