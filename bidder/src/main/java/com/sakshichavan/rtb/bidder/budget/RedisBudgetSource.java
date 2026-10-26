package com.sakshichavan.rtb.bidder.budget;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CompletionStage;

/**
 * Campaign budgets in Redis as hashes {total, spent}, keyed by UTC date so they reset daily.
 * The lease script enforces both the budget and the pacing cap atomically: concurrent bidders can never
 * overspend, and pacing applies globally across all instances with no extra coordination.
 * One Lettuce connection is shared by all threads; commands are pipelined and never block.
 */
public final class RedisBudgetSource implements BudgetSource {

    static final String INIT_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return 0
            end
            redis.call('HSET', KEYS[1], 'total', ARGV[1], 'spent', 0)
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """;

    static final String LEASE_SCRIPT = """
            local total = tonumber(redis.call('HGET', KEYS[1], 'total') or '0')
            if total <= 0 then
              return 0
            end
            local spent = tonumber(redis.call('HGET', KEYS[1], 'spent') or '0')
            local allowed = math.min(total, math.floor(total * tonumber(ARGV[2])))
            local available = allowed - spent
            if available <= 0 then
              return 0
            end
            local grant = math.min(available, tonumber(ARGV[1]))
            redis.call('HINCRBY', KEYS[1], 'spent', grant)
            return grant
            """;

    private static final Duration KEY_TTL = Duration.ofDays(2);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> commands;
    private final Clock clock;
    private final PacingPolicy pacing;

    public RedisBudgetSource(String redisUri, Clock clock) {
        this(redisUri, clock, PacingPolicy.NONE);
    }

    public RedisBudgetSource(String redisUri, Clock clock, PacingPolicy pacing) {
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect();
        this.commands = connection.async();
        this.clock = clock;
        this.pacing = pacing;
    }

    @Override
    public CompletionStage<Long> lease(String campaignId, long requestedMicros) {
        if (requestedMicros <= 0) {
            throw new IllegalArgumentException("requested amount must be positive: " + requestedMicros);
        }
        double allowedFraction = pacing.allowedFraction(clock.instant());
        return commands.eval(LEASE_SCRIPT, ScriptOutputType.INTEGER,
                new String[] {key(campaignId)},
                String.valueOf(requestedMicros), String.valueOf(allowedFraction));
    }

    @Override
    public CompletionStage<Boolean> initialize(String campaignId, long budgetMicros) {
        RedisFuture<Long> created = commands.eval(INIT_SCRIPT, ScriptOutputType.INTEGER,
                new String[] {key(campaignId)},
                String.valueOf(budgetMicros), String.valueOf(KEY_TTL.toSeconds()));
        return created.thenApply(result -> result != null && result == 1L);
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
