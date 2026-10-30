package com.sakshichavan.rtb.billing;

import com.sakshichavan.rtb.common.events.SettlementEvent;
import com.sakshichavan.rtb.common.openrtb.OpenRtbJson;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads settlement events and records them in the billing ledger exactly once.
 * Offsets live in Postgres, written in the same transaction as the events; on startup the consumer seeks to them.
 * read_committed is essential: the input is written by Flink's transactional sink, and reading uncommitted data
 * would bill events from transactions Flink later aborts and rewrites.
 * After a failed batch the consumer stops: Kafka's in-memory position has moved past the rolled-back batch,
 * so only a restart (re-seeking from Postgres) is safe.
 */
public final class BillingConsumer implements AutoCloseable {

    private final KafkaConsumer<String, byte[]> consumer;
    private final BillingStore store;
    private final String name;
    private final String topic;
    private boolean failed;

    public BillingConsumer(String bootstrapServers, String topic, String name, BillingStore store, int maxPollRecords)
            throws SQLException {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, name);
        this.consumer = new KafkaConsumer<>(config);
        this.store = store;
        this.name = name;
        this.topic = topic;
        try {
            seekToStoredOffsets();
        } catch (SQLException | RuntimeException e) {
            consumer.close();
            throw e;
        }
    }

    private void seekToStoredOffsets() throws SQLException {
        List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofSeconds(30));
        if (infos == null || infos.isEmpty()) {
            throw new IllegalStateException("topic not found: " + topic);
        }
        List<TopicPartition> partitions = infos.stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
        consumer.assign(partitions);
        Map<Integer, Long> stored = store.loadOffsets(name, topic);
        for (TopicPartition partition : partitions) {
            Long next = stored.get(partition.partition());
            if (next != null) {
                consumer.seek(partition, next);
            } else {
                consumer.seekToBeginning(List.of(partition));
            }
        }
    }

    /** Polls once and records whatever arrived in a single database transaction. */
    public BatchResult pollOnce(Duration timeout) throws SQLException {
        if (failed) {
            throw new IllegalStateException("a batch failed; restart the consumer to resume from the stored offsets");
        }
        ConsumerRecords<String, byte[]> records = consumer.poll(timeout);
        if (records.isEmpty()) {
            return BatchResult.EMPTY;
        }
        List<SettlementEvent> events = new ArrayList<>(records.count());
        Map<Integer, Long> nextOffsets = new HashMap<>();
        int rejected = 0;
        for (ConsumerRecord<String, byte[]> record : records) {
            nextOffsets.put(record.partition(), record.offset() + 1);
            SettlementEvent event = parseValid(record.value());
            if (event == null) {
                rejected++;
            } else {
                events.add(event);
            }
        }
        try {
            int inserted = store.record(name, topic, events, nextOffsets);
            return new BatchResult(records.count(), inserted, events.size() - inserted, rejected);
        } catch (SQLException | RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    /** Returns the event, or null if it is malformed or violates billing rules (skipped, not retried forever). */
    static SettlementEvent parseValid(byte[] value) {
        if (value == null) {
            return null;
        }
        SettlementEvent event;
        try {
            event = OpenRtbJson.read(value, SettlementEvent.class);
        } catch (RuntimeException e) {
            return null;
        }
        boolean valid = event.eventId() != null && event.bidId() != null && event.campaignId() != null
                && event.type() != null
                && event.reservedMicros() >= 0
                && event.chargedMicros() >= 0
                && event.chargedMicros() <= event.reservedMicros();
        return valid ? event : null;
    }

    @Override
    public void close() {
        consumer.close();
    }
}
