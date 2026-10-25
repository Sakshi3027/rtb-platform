package com.sakshichavan.rtb.common.openrtb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BidResponseTest {

    private static BidRequest sampleRequest() {
        Imp imp = new Imp("imp-1", new Banner(300, 250), 0.50, "USD");
        return new BidRequest("req-1", List.of(imp), null, null, null, 100, null);
    }

    private static Bid bid(String impId, double price) {
        return new Bid("bid-1", impId, price, "ad-1", "cr-1", "http://example.com/win");
    }

    @Test
    void createsSingleBidResponse() {
        BidResponse response = BidResponse.single("req-1", "seat-1", bid("imp-1", 1.25));

        assertEquals("req-1", response.id());
        assertEquals(1, response.seatBid().size());
        assertEquals(1.25, response.seatBid().get(0).bid().get(0).price());
    }

    @Test
    void defaultsCurrencyToUsd() {
        BidResponse response = new BidResponse(
                "req-1", List.of(new SeatBid("seat-1", List.of(bid("imp-1", 1.0)))), null);

        assertEquals("USD", response.cur());
    }

    @Test
    void rejectsNonPositivePrice() {
        assertThrows(IllegalArgumentException.class, () -> bid("imp-1", 0.0));
    }

    @Test
    void rejectsEmptySeatBidAndEmptyBids() {
        assertThrows(IllegalArgumentException.class, () -> new BidResponse("req-1", List.of(), "USD"));
        assertThrows(IllegalArgumentException.class, () -> new SeatBid("seat-1", List.of()));
    }

    @Test
    void isValidForMatchingRequest() {
        BidResponse response = BidResponse.single("req-1", "seat-1", bid("imp-1", 1.25));

        assertTrue(response.isValidFor(sampleRequest()));
    }

    @Test
    void isInvalidWhenRequestIdDoesNotMatch() {
        BidResponse response = BidResponse.single("req-999", "seat-1", bid("imp-1", 1.25));

        assertFalse(response.isValidFor(sampleRequest()));
    }

    @Test
    void isInvalidWhenBidTargetsUnknownImp() {
        BidResponse response = BidResponse.single("req-1", "seat-1", bid("imp-404", 1.25));

        assertFalse(response.isValidFor(sampleRequest()));
    }

    @Test
    void isInvalidWhenPriceBelowFloor() {
        BidResponse response = BidResponse.single("req-1", "seat-1", bid("imp-1", 0.10));

        assertFalse(response.isValidFor(sampleRequest()));
    }
}
