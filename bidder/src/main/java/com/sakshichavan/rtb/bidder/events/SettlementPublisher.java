package com.sakshichavan.rtb.bidder.events;

import com.sakshichavan.rtb.common.events.SettlementEvent;

/** Publishes settlement events. Must never block the caller: it runs on bid and notice threads. */
@FunctionalInterface
public interface SettlementPublisher {

    void publish(SettlementEvent event);

    SettlementPublisher NONE = event -> {
    };
}
