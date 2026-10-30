package com.sakshichavan.rtb.billing;

import com.sakshichavan.rtb.common.events.SettlementEvent;
import com.sakshichavan.rtb.common.events.SettlementType;
import com.sakshichavan.rtb.common.openrtb.OpenRtbJson;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 100 distinct events plus 20 duplicates; however the consumer is stopped or crashed, the ledger ends exact. */
@Testcontainers
class BillingConsumerTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.2"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final List<SettlementEvent> DISTINCT = IntStream.range(0, 100)
            .mapToObj(i -> (i % 2 == 0)
                    ? SettlementEvent.win("b" + i, "c" + (i % 4), 2_000, 1_000 + i, i)
                    : SettlementEvent.loss("b" + i, "c" + (i % 4), 2_000, i))
            .toList();
    private static final long EXPECTED_CHARGED = DISTINCT.stream()
            .filter(event -> event.type() == SettlementType.WIN)
            .mapToLong(SettlementEvent::chargedMicros)
            .sum();
    private static final BillingTotals EXPECTED = new BillingTotals(100, 50, EXPECTED_CHARGED);
    private static final int TOTAL_RECORDS = 120;

    private static BillingStore store(Runnable beforeCommit) throws SQLException {
        return new BillingStore(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), beforeCommit);
    }

    @BeforeEach
    void emptyTheLedger() throws SQLException {
        try (BillingStore schemaReady = store(() -> {
        });
             Connection connection = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE settlements, consumer_offsets");
        }
    }

    private static void publish(String topic) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        List<SettlementEvent> withDuplicates = new ArrayList<>(DISTINCT);
        withDuplicates.addAll(DISTINCT.subList(0, 20));
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(config)) {
            for (SettlementEvent event : withDuplicates) {
                producer.send(new ProducerRecord<>(topic, event.campaignId(), OpenRtbJson.write(event)));
            }
            producer.flush();
        }
    }

    private static BillingConsumer consumer(String topic, String name, BillingStore store) throws SQLException {
        return new BillingConsumer(KAFKA.getBootstrapServers(), topic, name, store, 10);
    }

    /** Polls until a batch with records arrives. */
    private static BatchResult nextBatch(BillingConsumer consumer) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() - deadline < 0) {
            BatchResult batch = consumer.pollOnce(Duration.ofMillis(500));
            if (batch.records() > 0) {
                return batch;
            }
        }
        throw new AssertionError("no records arrived");
    }

    /** Polls until every record in the topic has been consumed, according to the offsets stored in Postgres. */
    private static void drain(BillingConsumer consumer, BillingStore store, String name, String topic) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (storedPosition(store, name, topic) < TOTAL_RECORDS && System.nanoTime() - deadline < 0) {
            consumer.pollOnce(Duration.ofMillis(500));
        }
    }

    private static long storedPosition(BillingStore store, String name, String topic) throws SQLException {
        return store.loadOffsets(name, topic).values().stream().mapToLong(Long::longValue).sum();
    }

    @Test
    void stoppingMidStreamAndRestartingBillsEachEventOnce() throws Exception {
        String topic = "settlements-stop";
        publish(topic);

        try (BillingStore store = store(() -> {
        });
             BillingConsumer first = consumer(topic, "billing", store)) {
            for (int batch = 0; batch < 3; batch++) {
                nextBatch(first);
            }
        }

        try (BillingStore store = store(() -> {
        });
             BillingConsumer second = consumer(topic, "billing", store)) {
            drain(second, store, "billing", topic);
            assertEquals(EXPECTED, store.totals());
            assertEquals(TOTAL_RECORDS, storedPosition(store, "billing", topic));
        }
    }

    @Test
    void aBatchThatCrashesBeforeCommitIsReplayedNotLost() throws Exception {
        String topic = "settlements-crash";
        publish(topic);
        AtomicInteger commits = new AtomicInteger();

        try (BillingStore crashing = store(() -> {
            if (commits.incrementAndGet() == 2) {
                throw new IllegalStateException("simulated crash before commit");
            }
        });
             BillingConsumer first = consumer(topic, "billing", crashing)) {
            nextBatch(first);
            assertThrows(IllegalStateException.class, () -> nextBatch(first));
            assertThrows(IllegalStateException.class, () -> first.pollOnce(Duration.ofMillis(100)),
                    "a failed consumer must refuse to continue");
        }

        try (BillingStore store = store(() -> {
        });
             BillingConsumer second = consumer(topic, "billing", store)) {
            drain(second, store, "billing", topic);
            assertEquals(EXPECTED, store.totals());
        }
    }
}
