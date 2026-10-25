package com.sakshichavan.rtb.bidder.budget;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class RedisBudgetSourceTest extends BudgetSourceContract {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static String redisUri() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Override
    protected BudgetSource newSource() {
        return new RedisBudgetSource(redisUri(), Clock.systemUTC());
    }

    @Test
    void keysAreScopedToTheUtcDate() {
        try (RedisBudgetSource day = new RedisBudgetSource(redisUri(), fixedClock("2026-10-25T12:00:00Z"))) {
            assertEquals("budget:2026-10-25:c-1", day.key("c-1"));
        }
    }

    @Test
    void budgetsResetOnANewDay() {
        String campaign = freshCampaign();

        try (RedisBudgetSource dayOne = new RedisBudgetSource(redisUri(), fixedClock("2026-10-25T23:59:00Z"))) {
            dayOne.initialize(campaign, 5_000).toCompletableFuture().join();
            assertEquals(5_000, dayOne.lease(campaign, 5_000).toCompletableFuture().join());
            assertEquals(0, dayOne.lease(campaign, 5_000).toCompletableFuture().join());
        }

        try (RedisBudgetSource dayTwo = new RedisBudgetSource(redisUri(), fixedClock("2026-10-26T00:01:00Z"))) {
            dayTwo.initialize(campaign, 5_000).toCompletableFuture().join();
            assertEquals(5_000, dayTwo.lease(campaign, 5_000).toCompletableFuture().join());
        }
    }
}
