package com.sakshichavan.rtb.streams;

import com.sakshichavan.rtb.common.events.SettlementEvent;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Attaching the real Kafka sink runs Flink's type and lineage checks without needing a Kafka broker. */
class DedupJobGraphTest {

    @Test
    void theProductionSinkAttachesToTheDedupStream() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        List<SettlementEvent> sample = List.of(SettlementEvent.loss("b1", "c1", 1_000, 1L));

        assertDoesNotThrow(() -> SettlementDedup.apply(env.fromData(sample), Duration.ofHours(1))
                .sinkTo(DedupJobMain.sink("localhost:9092", "test-topic")));
    }
}
