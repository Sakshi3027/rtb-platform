package com.sakshichavan.rtb.bidder.profile;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class AerospikeProfileStoreTest {

    @Container
    static final GenericContainer<?> AEROSPIKE = new GenericContainer<>(DockerImageName.parse("aerospike:ce-8.1.2.4"))
            .withExposedPorts(3000)
            .waitingFor(Wait.forLogMessage(".*service ready: soon there will be cake!.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    private static AerospikeProfileStore store;

    @BeforeAll
    static void connect() throws InterruptedException {
        store = AerospikeProfileStore.connectWithRetry(
                AEROSPIKE.getHost(), AEROSPIKE.getMappedPort(3000), "test", 1_000, Duration.ofSeconds(30));
    }

    @AfterAll
    static void disconnect() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void storesAndReadsBackSegments() {
        UserProfile profile = new UserProfile(Set.of("traveler", "gamer"));
        store.put("u-roundtrip", profile);

        assertEquals(profile, store.lookup("u-roundtrip").toCompletableFuture().join());
    }

    @Test
    void unknownUserIsAMissNotAnError() {
        assertEquals(UserProfile.UNKNOWN, store.lookup("u-never-seen").toCompletableFuture().join());
    }

    @Test
    void handlesManyConcurrentAsyncLookups() {
        store.put("u-hot", new UserProfile(Set.of("sports-fan")));
        List<CompletableFuture<UserProfile>> lookups = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            lookups.add(store.lookup("u-hot").toCompletableFuture());
        }

        CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new)).join();

        for (CompletableFuture<UserProfile> lookup : lookups) {
            assertEquals(Set.of("sports-fan"), lookup.join().segments());
        }
    }
}
