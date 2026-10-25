package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.common.openrtb.AuctionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BidPricerTest {

    private final BidPricer pricer = new BidPricer(0.5);

    @Test
    void secondPriceBidsFullValue() {
        assertEquals(3.00, pricer.price(3.00, 1.00, AuctionType.SECOND_PRICE));
    }

    @Test
    void firstPriceShadesHalfwayBetweenFloorAndValue() {
        assertEquals(2.00, pricer.price(3.00, 1.00, AuctionType.FIRST_PRICE));
    }

    @Test
    void shadingFactorOfOneMeansNoShading() {
        assertEquals(3.00, new BidPricer(1.0).price(3.00, 1.00, AuctionType.FIRST_PRICE));
    }

    @Test
    void roundingIsImmuneToFloatingPointNoise() {
        // 0.10 + 0.5 * (0.50 - 0.10) is 0.30000000000000004 in floating point.
        // A naive ceil would round that up to 0.31.
        assertEquals(0.30, pricer.price(0.50, 0.10, AuctionType.FIRST_PRICE));
    }

    @Test
    void priceAlwaysStaysBetweenFloorAndValue() {
        for (int floorCents = 0; floorCents <= 300; floorCents += 7) {
            for (int valueCents = Math.max(floorCents, 1); valueCents <= 300; valueCents += 11) {
                double floor = floorCents / 100.0;
                double value = valueCents / 100.0;

                double price = pricer.price(value, floor, AuctionType.FIRST_PRICE);

                assertTrue(price >= floor && price <= value,
                        "price " + price + " outside [" + floor + ", " + value + "]");
            }
        }
    }

    @Test
    void rejectsInvalidShadingFactors() {
        assertThrows(IllegalArgumentException.class, () -> new BidPricer(0.0));
        assertThrows(IllegalArgumentException.class, () -> new BidPricer(1.5));
    }
}
