package com.sakshichavan.rtb.streams;

import com.sakshichavan.rtb.common.events.SettlementEvent;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.datastream.DataStream;

import java.time.Duration;

/** The dedup step, separate from sources and sinks so it can be tested with in-memory data. */
public final class SettlementDedup {

    private SettlementDedup() {
    }

    public static DataStream<SettlementEvent> apply(DataStream<SettlementEvent> events, Duration ttl) {
        return events
                .keyBy(SettlementEvent::eventId, Types.STRING)
                .process(new FirstSeenFilter(ttl))
                .uid("dedup-by-event-id")
                .name("dedup by event id");
    }
}
