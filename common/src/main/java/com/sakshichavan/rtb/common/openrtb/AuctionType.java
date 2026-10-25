package com.sakshichavan.rtb.common.openrtb;

/**
 * OpenRTB auction type. On the wire it is an integer: 1 = first price, 2 = second price.
 * First price: the winner pays its own bid. Second price: the winner pays the second-highest bid.
 */
public enum AuctionType {
    FIRST_PRICE(1),
    SECOND_PRICE(2);

    private final int code;

    AuctionType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static AuctionType fromCode(int code) {
        for (AuctionType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown auction type: " + code);
    }
}
