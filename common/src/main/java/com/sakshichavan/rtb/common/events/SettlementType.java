package com.sakshichavan.rtb.common.events;

/** How a bid's reservation was settled. Each bid settles exactly once. */
public enum SettlementType {
    WIN,
    LOSS,
    EXPIRED
}
