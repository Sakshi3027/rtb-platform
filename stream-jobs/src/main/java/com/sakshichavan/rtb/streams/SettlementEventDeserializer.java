package com.sakshichavan.rtb.streams;

import com.sakshichavan.rtb.common.events.SettlementEvent;
import com.sakshichavan.rtb.common.openrtb.OpenRtbJson;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Turns Kafka records into SettlementEvents. A malformed record is skipped rather than failing the job:
 * one bad message must not stop billing for everyone (a "poison pill").
 */
public final class SettlementEventDeserializer implements KafkaRecordDeserializationSchema<SettlementEvent> {

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<SettlementEvent> out) {
        if (record.value() == null) {
            return;
        }
        try {
            out.collect(OpenRtbJson.read(record.value(), SettlementEvent.class));
        } catch (RuntimeException e) {
            // skip the poison pill; production would route it to a dead-letter topic for inspection
        }
    }

    @Override
    public TypeInformation<SettlementEvent> getProducedType() {
        return TypeInformation.of(SettlementEvent.class);
    }
}
