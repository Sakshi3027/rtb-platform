package com.sakshichavan.rtb.streams;

import com.sakshichavan.rtb.common.events.SettlementEvent;
import org.apache.flink.api.common.serialization.SerializationSchema;

import java.nio.charset.StandardCharsets;

/**
 * Kafka record key: the campaign id, so each campaign's events share a partition.
 * A named class rather than a lambda: Flink reads the generic type from the class declaration,
 * which lambdas lose to type erasure.
 */
public final class CampaignKeySerializer implements SerializationSchema<SettlementEvent> {

    @Override
    public byte[] serialize(SettlementEvent event) {
        return event.campaignId().getBytes(StandardCharsets.UTF_8);
    }
}
