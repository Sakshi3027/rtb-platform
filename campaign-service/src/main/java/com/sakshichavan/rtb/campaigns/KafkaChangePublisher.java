package com.sakshichavan.rtb.campaigns;

import com.sakshichavan.rtb.common.campaigns.CampaignUpdate;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;

/**
 * Publishes campaign changes to a compacted topic, keyed by campaign id, so the topic always holds every campaign's
 * latest state. Keying by campaign keeps each campaign's versions in order within one partition.
 * Unlike the bidder's publisher, this one may block: it runs on the relay's own thread, never on a request path.
 */
public final class KafkaChangePublisher implements ChangePublisher {

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;

    public KafkaChangePublisher(String bootstrapServers, String topic) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        this.producer = new KafkaProducer<>(config);
        this.topic = topic;
    }

    @Override
    public void publish(List<CampaignChange> changes) throws Exception {
        List<Future<RecordMetadata>> sent = new ArrayList<>(changes.size());
        for (CampaignChange change : changes) {
            CampaignUpdate update = new CampaignUpdate(change.revision(), change.version(), change.campaign());
            sent.add(producer.send(new ProducerRecord<>(topic, change.campaignId(), CampaignJson.writeBytes(update))));
        }
        producer.flush();
        for (Future<RecordMetadata> result : sent) {
            result.get();
        }
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(5));
    }
}
