package com.sakshichavan.rtb.common.openrtb;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BidRequestTest {

    private static Imp sampleImp() {
        return new Imp("imp-1", new Banner(300, 250), 0.50, "USD");
    }

    @Test
    void createsValidRequest() {
        BidRequest request = new BidRequest(
                "req-1", List.of(sampleImp()), null, null, null, 100, AuctionType.FIRST_PRICE);

        assertEquals("req-1", request.id());
        assertEquals(1, request.imp().size());
        assertEquals(100, request.tmax());
    }

    @Test
    void rejectsRequestWithNoImpressions() {
        assertThrows(IllegalArgumentException.class,
                () -> new BidRequest("req-1", List.of(), null, null, null, 100, null));
    }

    @Test
    void rejectsNonPositiveTmax() {
        assertThrows(IllegalArgumentException.class,
                () -> new BidRequest("req-1", List.of(sampleImp()), null, null, null, 0, null));
    }

    @Test
    void defaultsToSecondPriceAuction() {
        BidRequest request = new BidRequest("req-1", List.of(sampleImp()), null, null, null, 100, null);

        assertEquals(AuctionType.SECOND_PRICE, request.at());
    }

    @Test
    void impListCannotBeChangedFromOutside() {
        List<Imp> original = new ArrayList<>(List.of(sampleImp()));
        BidRequest request = new BidRequest("req-1", original, null, null, null, 100, null);

        original.clear();

        assertEquals(1, request.imp().size());
        assertThrows(UnsupportedOperationException.class, () -> request.imp().clear());
    }

    @Test
    void mapsAuctionTypeFromWireCode() {
        assertEquals(AuctionType.FIRST_PRICE, AuctionType.fromCode(1));
        assertEquals(AuctionType.SECOND_PRICE, AuctionType.fromCode(2));
        assertThrows(IllegalArgumentException.class, () -> AuctionType.fromCode(3));
    }

    @Test
    void rejectsInvalidBannerAndFloor() {
        assertThrows(IllegalArgumentException.class, () -> new Banner(0, 250));
        assertThrows(IllegalArgumentException.class,
                () -> new Imp("imp-1", new Banner(300, 250), -1.0, "USD"));
    }
}
