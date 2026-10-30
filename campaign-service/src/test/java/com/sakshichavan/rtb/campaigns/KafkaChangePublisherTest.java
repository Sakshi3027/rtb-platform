package com.sakshichavan.rtb.campaigns;

import com.sakshichavan.rtb.common.campaigns.CampaignSpec;
import com.sakshichavan.rtb.common.campaigns.CampaignStatus;
import com.sakshichavan.rtb.common.campaigns.CampaignUpdate;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class KafkaChangePublisherTest {

    private static final String TOPIC = "rtb.campaigns";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.2"));

    private static CampaignChange change(long revision, String id, long version, double price) {
        return new CampaignChange(revision, id, version,
                new CampaignSpec(id, "cr-1", price, 10.0, null, null, CampaignStatus.ACTIVE));
    }

    @Test
    void publishesFullCampaignStatesKeyedByCampaignInVersionOrder() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1)
                    .configs(Map.of("cleanup.policy", "compact")))).all().get(30, TimeUnit.SECONDS);
        }
        try (KafkaChangePublisher publisher = new KafkaChangePublisher(KAFKA.getBootstrapServers(), TOPIC)) {
            publisher.publish(List.of(change(1, "c-a", 1, 1.00), change(2, "c-b", 1, 2.00), change(3, "c-a", 2, 1.50)));
        }

        List<ConsumerRecord<String, byte[]>> records = consumeAll(3);
        List<CampaignUpdate> campaignA = new ArrayList<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            CampaignUpdate update = CampaignJson.MAPPER.readValue(record.value(), CampaignUpdate.class);
            assertEquals(update.campaign().id(), record.key(), "keyed by campaign id");
            if (record.key().equals("c-a")) {
                campaignA.add(update);
            }
        }

        assertEquals(3, records.size());
        assertEquals(List.of(1L, 2L), campaignA.stream().map(CampaignUpdate::version).toList(), "versions in order");
        assertEquals(1.50, campaignA.get(1).campaign().maxBidCpm(), "each message carries the full campaign state");
    }

    private static List<ConsumerRecord<String, byte[]>> consumeAll(int expected) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "campaign-test");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (records.size() < expected && System.nanoTime() - deadline < 0) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
        }
        return records;
    }
}
