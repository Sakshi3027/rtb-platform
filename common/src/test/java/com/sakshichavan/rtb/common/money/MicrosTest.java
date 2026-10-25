package com.sakshichavan.rtb.common.money;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MicrosTest {

    @Test
    void convertsDollarsExactlyDespiteFloatingPointNoise() {
        assertEquals(300_000L, Micros.fromDollars(0.1 + 0.2));
    }

    @Test
    void convertsBackToDollars() {
        assertEquals(2.5, Micros.toDollars(2_500_000L));
    }

    @Test
    void impressionCostIsCpmDividedByOneThousand() {
        assertEquals(2_000L, Micros.impressionCost(2.00));
        assertEquals(1_250L, Micros.impressionCost(1.25));
    }
}
