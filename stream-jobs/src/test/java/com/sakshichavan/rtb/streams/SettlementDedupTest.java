package com.sakshichavan.rtb.streams;

import com.sakshichavan.rtb.common.events.SettlementEvent;
import com.sakshichavan.rtb.common.events.SettlementType;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettlementDedupTest {

    private static List<SettlementEvent> run(List<SettlementEvent> input) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);
        DataStream<SettlementEvent> events = env.fromData(input);
        List<SettlementEvent> output = new ArrayList<>();
        try (CloseableIterator<SettlementEvent> results = SettlementDedup.apply(events, Duration.ofHours(1)).executeAndCollect()) {
            results.forEachRemaining(output::add);
        }
        return output;
    }

    @Test
    void dropsRepeatedCopiesOfTheSameEvent() throws Exception {
        List<SettlementEvent> input = List.of(
                SettlementEvent.win("b1", "c1", 2_000, 1_000, 1L),
                SettlementEvent.loss("b2", "c1", 1_500, 2L),
                SettlementEvent.win("b1", "c1", 2_000, 1_000, 1L),
                SettlementEvent.win("b1", "c1", 2_000, 1_000, 1L),
                SettlementEvent.expired("b3", "c2", 1_200, 3L),
                SettlementEvent.loss("b2", "c1", 1_500, 2L));

        List<SettlementEvent> output = run(input);

        assertEquals(3, output.size());
        assertEquals(Set.of("settlement-b1", "settlement-b2", "settlement-b3"),
                output.stream().map(SettlementEvent::eventId).collect(Collectors.toSet()));
    }

    @Test
    void keepsTheFirstCopyWhenCopiesDisagree() throws Exception {
        SettlementEvent original = SettlementEvent.win("b1", "c1", 2_000, 1_000, 1L);
        SettlementEvent conflicting = new SettlementEvent(
                "settlement-b1", SettlementType.WIN, "b1", "c1", 2_000, 9_999, 5L);

        List<SettlementEvent> output = run(List.of(original, conflicting));

        assertEquals(List.of(original), output);
    }

    @Test
    void passesEveryDistinctEventThrough() throws Exception {
        List<SettlementEvent> input = IntStream.range(0, 100)
                .mapToObj(i -> SettlementEvent.loss("b" + i, "c" + (i % 5), 1_000, i))
                .toList();

        List<SettlementEvent> output = run(input);

        assertEquals(100, output.size());
    }
}
