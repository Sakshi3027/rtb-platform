package com.sakshichavan.rtb.billing;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the billing consumer until stopped. A crash at any point is safe: restart resumes from Postgres. */
public final class BillingMain {

    private static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private BillingMain() {
    }

    private static String env(String name, String defaultValue) {
        return System.getenv().getOrDefault(name, defaultValue);
    }

    public static void main(String[] args) throws Exception {
        String bootstrap = env("KAFKA_BOOTSTRAP", "localhost:9092");
        String topic = env("TOPIC", "rtb.settlements.deduped");
        String jdbcUrl = env("JDBC_URL", "jdbc:postgresql://localhost:5432/billing");
        String user = env("DB_USER", "rtb");
        String password = env("DB_PASSWORD", "rtb");
        String name = env("CONSUMER_NAME", "billing");

        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            try {
                stopped.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        try (BillingStore store = new BillingStore(jdbcUrl, user, password);
             BillingConsumer consumer = new BillingConsumer(bootstrap, topic, name, store, 500)) {
            System.out.printf("billing consumer '%s': %s -> %s, resuming from stored offsets %s%n",
                    name, topic, jdbcUrl, store.loadOffsets(name, topic));

            long records = 0;
            long inserted = 0;
            long duplicates = 0;
            long rejected = 0;
            long reportedRecords = 0;
            long lastReport = System.nanoTime();
            while (running.get()) {
                BatchResult batch = consumer.pollOnce(Duration.ofMillis(500));
                records += batch.records();
                inserted += batch.inserted();
                duplicates += batch.duplicates();
                rejected += batch.rejected();
                if (System.nanoTime() - lastReport >= REPORT_INTERVAL_NANOS && records != reportedRecords) {
                    BillingTotals totals = store.totals();
                    System.out.printf("read %d | inserted %d | duplicates %d | rejected %d || ledger: %d settlements, "
                                    + "%d wins, %d micros charged%n",
                            records, inserted, duplicates, rejected,
                            totals.settlements(), totals.wins(), totals.chargedMicros());
                    reportedRecords = records;
                    lastReport = System.nanoTime();
                }
            }
        } finally {
            stopped.countDown();
        }
    }
}
