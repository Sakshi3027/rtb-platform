package com.sakshichavan.rtb.common.openrtb;

/** Ad slot dimensions in pixels (OpenRTB "banner" object). */
public record Banner(int w, int h) {

    public Banner {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("banner size must be positive: " + w + "x" + h);
        }
    }
}
