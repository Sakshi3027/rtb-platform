package com.sakshichavan.rtb.common.openrtb;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/** One impression (ad slot) being auctioned. bidFloor is the minimum CPM price. */
public record Imp(
        String id,
        Banner banner,
        @JsonProperty("bidfloor") double bidFloor,
        @JsonProperty("bidfloorcur") String bidFloorCur) {

    public Imp {
        Objects.requireNonNull(id, "imp.id is required");
        Objects.requireNonNull(banner, "imp.banner is required");
        if (bidFloor < 0) {
            throw new IllegalArgumentException("bidFloor cannot be negative: " + bidFloor);
        }
        if (bidFloorCur == null) {
            bidFloorCur = "USD";
        }
    }
}
