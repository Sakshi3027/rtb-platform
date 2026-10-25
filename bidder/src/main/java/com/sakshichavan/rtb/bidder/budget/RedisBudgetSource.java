package com.sakshichavan.rtb.bidder.budget;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CompletionStage;

/**
 * Campaign budgets in Redis, keyed by UTC date so they reset daily with no cleanup job.
 * Leasing runs as a Lua script, which Redis executes atomically: read, partial grant, and decrement
 * happen with no gap, so concurrent bidders can never drive a budget negative.
 * One Lettuce connection is shared by all threads; commands are pipelined and never block.
 */
public final class RedisBudgetSource implements BudgetSource {

    static final String LEASE_SCRIPT = """
            local remaining = tonumber(redis.call('GET', KEYS[1]) or '0')
            if remaining <= 0 then
              return 0
            end
            local grant = math.min(remaining, tonumber(ARGV[1]))
            redis.call('DECRBY', KEYS[1], grant)
            return grant
            """;

    private static final Duration KEY_TTL = Duration.ofDays(2);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> commands;
    private final Clock clock;

    public RedisBudgetSource(String redisUri, Clock clock) {
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect();
        this.commands = connection.async();
        this.clock = clock;
    }

    @Override
    public CompletionStage<Long> lease(String campaignId, long requestedMicros) {
        if (requestedMicros <= 0) {
            throw new IllegalArgumentException("requested amount must be positive: " + requestedMicros);
        }
        return commands.eval(LEASE_SCRIPT, ScriptOutputType.INTEGER,
                new String[] {key(campaignId)}, String.valueOf(requestedMicros));
    }

    @Override
    public CompletionStage<Boolean> initialize(String campaignId, long budgetMicros) {
        SetArgs onlyIfAbsent = SetArgs.Builder.nx().ex(KEY_TTL);
        return commands.set(key(campaignId), String.valueOf(budgetMicros), onlyIfAbsent)
                .thenApply("OK"::equals);
    }

    String key(String campaignId) {
        return "budget:" + LocalDate.now(clock) + ":" + campaignId;
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
