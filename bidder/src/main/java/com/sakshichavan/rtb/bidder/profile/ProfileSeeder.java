package com.sakshichavan.rtb.bidder.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads synthetic profiles for user-0 .. user-(N-1) into Aerospike, matching the simulator's user ids.
 * Users with no segments get no record, so lookups for them are realistic misses.
 * Usage: java -cp bidder.jar com.sakshichavan.rtb.bidder.profile.ProfileSeeder [userCount]
 */
public final class ProfileSeeder {

    private static final int WRITERS = 32;

    private ProfileSeeder() {
    }

    public static void main(String[] args) throws Exception {
        String host = System.getenv().getOrDefault("AEROSPIKE_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("AEROSPIKE_PORT", "3000"));
        String namespace = System.getenv().getOrDefault("AEROSPIKE_NAMESPACE", "test");
        int users = (args.length > 0) ? Integer.parseInt(args[0]) : 100_000;

        long start = System.nanoTime();
        AtomicInteger written = new AtomicInteger();
        try (AerospikeProfileStore store = new AerospikeProfileStore(host, port, namespace, 1_000)) {
            ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
            try {
                List<Future<?>> writers = new ArrayList<>();
                for (int w = 0; w < WRITERS; w++) {
                    int offset = w;
                    writers.add(pool.submit(() -> {
                        for (int i = offset; i < users; i += WRITERS) {
                            String userId = "user-" + i;
                            UserProfile profile = SyntheticProfiles.forUser(userId);
                            if (!profile.segments().isEmpty()) {
                                store.put(userId, profile);
                                written.incrementAndGet();
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> writer : writers) {
                    writer.get();
                }
            } finally {
                pool.shutdown();
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("seeded %d profiles for %d users in %d ms%n", written.get(), users, millis);
    }
}
