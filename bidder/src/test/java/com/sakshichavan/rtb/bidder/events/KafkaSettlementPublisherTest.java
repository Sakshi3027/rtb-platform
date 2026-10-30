package com.sakshichavan.rtb.bidder.events;

import com.sakshichavan.rtb.common.events.SettlementEvent;
import com.sakshichavan.rtb.common.openrtb.OpenRtbJson;
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
class KafkaSettlementPublisherTest {

    private static final String TOPIC = "rtb.settlements";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.2"));

    @Test
    void publishesEventsKeyedByCampaignInOrder() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        List<SettlementEvent> sent = List.of(
                SettlementEvent.win("b1", "c1", 2_000, 1_000, 1L),
                SettlementEvent.loss("b2", "c2", 1_500, 2L),
                SettlementEvent.win("b3", "c1", 2_000, 1_200, 3L));

        KafkaSettlementPublisher publisher = new KafkaSettlementPublisher(KAFKA.getBootstrapServers(), TOPIC);
        try (publisher) {
            publisher.awaitReady(Duration.ofSeconds(30));
            sent.forEach(publisher::publish);
        }
        assertEquals(new PublisherStats(3, 3, 0), publisher.stats());

        List<ConsumerRecord<String, byte[]>> received = consume(3);
        List<SettlementEvent> events = received.stream()
                .map(record -> OpenRtbJson.read(record.value(), SettlementEvent.class))
                .toList();

        assertEquals(3, events.size());
        for (ConsumerRecord<String, byte[]> record : received) {
            SettlementEvent event = OpenRtbJson.read(record.value(), SettlementEvent.class);
            assertEquals(event.campaignId(), record.key(), "events must be keyed by campaign");
        }
        List<String> campaignOneBids = events.stream()
                .filter(event -> event.campaignId().equals("c1"))
                .map(SettlementEvent::bidId)
                .toList();
        assertEquals(List.of("b1", "b3"), campaignOneBids, "one campaign's events must stay in order");
        assertEquals(sent.stream().sorted((a, b) -> a.bidId().compareTo(b.bidId())).toList(),
                events.stream().sorted((a, b) -> a.bidId().compareTo(b.bidId())).toList());
    }

    private static List<ConsumerRecord<String, byte[]>> consume(int expected) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "settlement-test");
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
