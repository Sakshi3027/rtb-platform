package com.sakshichavan.rtb.streams;

import com.sakshichavan.rtb.common.events.SettlementEvent;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;

/**
 * Passes the first event for each key and drops every later one seen within the TTL.
 * Runs after keyBy(eventId), so all copies of an event reach the same instance and share one "seen" flag.
 * The flag expires after the TTL: duplicates older than that pass through, which bounds state size.
 */
public final class FirstSeenFilter extends KeyedProcessFunction<String, SettlementEvent, SettlementEvent> {

    private final Duration ttl;
    private transient ValueState<Boolean> seen;
    private transient Counter duplicatesDropped;

    public FirstSeenFilter(Duration ttl) {
        this.ttl = ttl;
    }

    @Override
    public void open(OpenContext openContext) {
        StateTtlConfig ttlConfig = StateTtlConfig.newBuilder(ttl)
                .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                .build();
        ValueStateDescriptor<Boolean> descriptor = new ValueStateDescriptor<>("seen", Boolean.class);
        descriptor.enableTimeToLive(ttlConfig);
        seen = getRuntimeContext().getState(descriptor);
        duplicatesDropped = getRuntimeContext().getMetricGroup().counter("duplicatesDropped");
    }

    @Override
    public void processElement(SettlementEvent event, Context context, Collector<SettlementEvent> out) throws Exception {
        if (seen.value() != null) {
            duplicatesDropped.inc();
            return;
        }
        seen.update(Boolean.TRUE);
        out.collect(event);
    }
}
