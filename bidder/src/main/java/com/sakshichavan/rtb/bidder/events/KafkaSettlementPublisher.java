package com.sakshichavan.rtb.bidder.events;

import com.sakshichavan.rtb.common.events.SettlementEvent;
import com.sakshichavan.rtb.common.openrtb.OpenRtbJson;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;

/**
 * Publishes settlement events to Kafka, keyed by campaign so each campaign's events stay ordered.
 * acks=all and producer idempotence prevent lost or duplicated writes on network retries.
 * max.block.ms is tiny because send() runs on bidder threads that must never block; a send that cannot
 * proceed is counted as failed instead. (Production would add a local outbox so such events are not lost.)
 */
public final class KafkaSettlementPublisher implements SettlementPublisher, AutoCloseable {

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;
    private final LongAdder published = new LongAdder();
    private final LongAdder acked = new LongAdder();
    private final LongAdder failed = new LongAdder();

    public KafkaSettlementPublisher(String bootstrapServers, String topic) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10);
        this.producer = new KafkaProducer<>(config);
        this.topic = topic;
    }

    /** Loads the topic's metadata up front, so later sends never wait for it. Call once at startup. */
    public void awaitReady(Duration maxWait) throws InterruptedException {
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (true) {
            try {
                producer.partitionsFor(topic);
                return;
            } catch (TimeoutException e) {
                if (System.nanoTime() - deadline >= 0) {
                    throw new IllegalStateException("Kafka topic " + topic + " not reachable within " + maxWait, e);
                }
                Thread.sleep(200);
            }
        }
    }

    @Override
    public void publish(SettlementEvent event) {
        published.increment();
        ProducerRecord<String, byte[]> record =
                new ProducerRecord<>(topic, event.campaignId(), OpenRtbJson.write(event));
        try {
            producer.send(record, (metadata, error) -> {
                if (error == null) {
                    acked.increment();
                } else {
                    failed.increment();
                }
            });
        } catch (KafkaException e) {
            failed.increment();
        }
    }

    public PublisherStats stats() {
        return new PublisherStats(published.sum(), acked.sum(), failed.sum());
    }

    /** Flushes buffered events, waiting up to 5 seconds, then closes the producer. */
    @Override
    public void close() {
        producer.close(Duration.ofSeconds(5));
    }
}
