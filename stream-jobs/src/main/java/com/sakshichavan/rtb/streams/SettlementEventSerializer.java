package com.sakshichavan.rtb.streams;

import com.sakshichavan.rtb.common.events.SettlementEvent;
import com.sakshichavan.rtb.common.openrtb.OpenRtbJson;
import org.apache.flink.api.common.serialization.SerializationSchema;

/** Kafka record value: the event as JSON, in the same format the bidder publishes. */
public final class SettlementEventSerializer implements SerializationSchema<SettlementEvent> {

    @Override
    public byte[] serialize(SettlementEvent event) {
        return OpenRtbJson.write(event);
    }
}
