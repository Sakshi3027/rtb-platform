package com.sakshichavan.rtb.streams;

import com.sakshichavan.rtb.common.events.SettlementEvent;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.kafka.clients.producer.ProducerConfig;

import java.time.Duration;

/**
 * Reads settlement events, drops duplicate event ids within the TTL, and writes each event exactly once.
 * Exactly-once output: the sink writes inside Kafka transactions that commit only when a checkpoint completes,
 * so a crash can never expose partially written or repeated output to read_committed consumers.
 */
public final class DedupJobMain {

    private static final long CHECKPOINT_INTERVAL_MILLIS = 5_000;
    /** Brokers reject transaction timeouts above 15 minutes by default; Flink's default is 1 hour. */
    private static final String TRANSACTION_TIMEOUT_MILLIS = "900000";

    private DedupJobMain() {
    }

    private static String env(String name, String defaultValue) {
        return System.getenv().getOrDefault(name, defaultValue);
    }

    static KafkaSource<SettlementEvent> source(String bootstrap, String topic) {
        return KafkaSource.<SettlementEvent>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("settlement-dedup")
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setDeserializer(new SettlementEventDeserializer())
                .build();
    }

    static KafkaSink<SettlementEvent> sink(String bootstrap, String topic) {
        return KafkaSink.<SettlementEvent>builder()
                .setBootstrapServers(bootstrap)
                .setRecordSerializer(KafkaRecordSerializationSchema.<SettlementEvent>builder()
                        .setTopic(topic)
                        .setKeySerializationSchema(new CampaignKeySerializer())
                        .setValueSerializationSchema(new SettlementEventSerializer())
                        .build())
                .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
                .setTransactionalIdPrefix("settlement-dedup")
                .setProperty(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, TRANSACTION_TIMEOUT_MILLIS)
                .build();
    }

    public static void main(String[] args) throws Exception {
        String bootstrap = env("KAFKA_BOOTSTRAP", "localhost:9092");
        String inputTopic = env("INPUT_TOPIC", "rtb.settlements");
        String outputTopic = env("OUTPUT_TOPIC", "rtb.settlements.deduped");
        Duration ttl = Duration.ofHours(Long.parseLong(env("DEDUP_TTL_HOURS", "24")));
        int parallelism = Integer.parseInt(env("PARALLELISM", "2"));

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(parallelism);
        env.enableCheckpointing(CHECKPOINT_INTERVAL_MILLIS);

        DataStream<SettlementEvent> events = env
                .fromSource(source(bootstrap, inputTopic), WatermarkStrategy.noWatermarks(), "settlements")
                .uid("settlements-source");
        SettlementDedup.apply(events, ttl)
                .sinkTo(sink(bootstrap, outputTopic))
                .uid("deduped-sink")
                .name("deduped settlements");

        System.out.printf("dedup job: %s -> %s via %s, ttl %s, parallelism %d, checkpoints every %dms%n",
                inputTopic, outputTopic, bootstrap, ttl, parallelism, CHECKPOINT_INTERVAL_MILLIS);
        env.execute("settlement-dedup");
    }
}
