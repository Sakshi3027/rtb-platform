package com.sakshichavan.rtb.common.openrtb;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenRtbJsonTest {

    private static BidRequest sampleRequest() {
        Imp imp = new Imp("imp-1", new Banner(300, 250), 0.5, "USD");
        Site site = new Site("site-1", "news.example.com", List.of("IAB12"));
        Device device = new Device("Mozilla/5.0", "203.0.113.7", new Geo("USA", "NY"));
        return new BidRequest("req-1", List.of(imp), site, device, new User("user-42"), 100, AuctionType.FIRST_PRICE);
    }

    private static String json(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void writesOpenRtbFieldNames() {
        String json = json(OpenRtbJson.write(sampleRequest()));

        assertTrue(json.contains("\"bidfloor\":0.5"));
        assertTrue(json.contains("\"at\":1"));
        assertFalse(json.contains("bidFloor"));
    }

    @Test
    void omitsNullFields() {
        Imp imp = new Imp("imp-1", new Banner(300, 250), 0.5, "USD");
        BidRequest request = new BidRequest("req-1", List.of(imp), null, null, null, 100, null);

        String json = json(OpenRtbJson.write(request));

        assertFalse(json.contains("user"));
        assertFalse(json.contains("device"));
    }

    @Test
    void roundTripsBidRequest() {
        BidRequest original = sampleRequest();

        BidRequest parsed = OpenRtbJson.readRequest(OpenRtbJson.write(original));

        assertEquals(original, parsed);
    }

    @Test
    void roundTripsBidResponse() {
        Bid bid = new Bid("bid-1", "imp-1", 1.25, "ad-1", "cr-1", "http://example.com/win");
        BidResponse original = BidResponse.single("req-1", "seat-1", bid);

        BidResponse parsed = OpenRtbJson.readResponse(OpenRtbJson.write(original));

        assertEquals(original, parsed);
        assertTrue(json(OpenRtbJson.write(original)).contains("\"seatbid\""));
    }

    @Test
    void parsesRealisticJsonAndIgnoresUnknownFields() {
        String json = """
                {
                  "id": "req-7",
                  "imp": [{"id": "imp-1", "banner": {"w": 728, "h": 90}, "bidfloor": 1.2}],
                  "tmax": 80,
                  "at": 2,
                  "regs": {"coppa": 0}
                }
                """;

        BidRequest request = OpenRtbJson.readRequest(json.getBytes(StandardCharsets.UTF_8));

        assertEquals("req-7", request.id());
        assertEquals(1.2, request.imp().get(0).bidFloor());
        assertEquals("USD", request.imp().get(0).bidFloorCur());
        assertEquals(AuctionType.SECOND_PRICE, request.at());
    }

    @Test
    void rejectsJsonThatBreaksValidation() {
        String json = """
                {"id": "req-8", "imp": [], "tmax": 100}
                """;

        assertThrows(UncheckedIOException.class,
                () -> OpenRtbJson.readRequest(json.getBytes(StandardCharsets.UTF_8)));
    }
}
