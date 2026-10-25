package com.sakshichavan.rtb.common.openrtb;

/** Viewer device: user agent, IP address, and location. */
public record Device(String ua, String ip, Geo geo) {
}
