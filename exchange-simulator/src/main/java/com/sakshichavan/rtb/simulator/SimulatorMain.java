package com.sakshichavan.rtb.simulator;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Command-line entry point.
 *   run  : drive open-loop load at a bidder and print a latency report
 *   stub : start the stub bidder and keep it running until Ctrl+C
 */
public final class SimulatorMain {

    private SimulatorMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }
        Map<String, String> flags = parseFlags(args);
        switch (args[0]) {
            case "run" -> runLoad(flags);
            case "stub" -> runStub(flags);
            default -> {
                printUsage();
                System.exit(1);
            }
        }
    }

    private static void runLoad(Map<String, String> flags) throws InterruptedException {
        URI target = URI.create(flags.getOrDefault("target", "http://127.0.0.1:8080/bid"));
        int qps = intFlag(flags, "qps", 1_000);
        int durationSeconds = intFlag(flags, "duration", 10);
        int warmupSeconds = intFlag(flags, "warmup", 3);
        long seed = Long.parseLong(flags.getOrDefault("seed", "42"));
        int users = intFlag(flags, "users", 100_000);

        WeightedSampler<Integer> userSampler = TrafficGenerator.userSampler(users);

        try (LoadDriver driver = new LoadDriver(target)) {
            if (warmupSeconds > 0) {
                System.out.printf("warming up: %ds at %d req/s (results discarded)%n", warmupSeconds, qps);
                driver.run(new TrafficGenerator(seed + 1, 1, userSampler), qps, Duration.ofSeconds(warmupSeconds));
            }
            System.out.printf("measuring: %ds at %d req/s against %s%n", durationSeconds, qps, target);
            LoadReport report = driver.run(
                    new TrafficGenerator(seed, 0, userSampler), qps, Duration.ofSeconds(durationSeconds));
            System.out.println(report.summary());
        }
    }

    private static void runStub(Map<String, String> flags) throws Exception {
        int port = intFlag(flags, "port", 8080);
        Duration delay = Duration.ofMillis(intFlag(flags, "delay-ms", 2));
        double bidRate = Double.parseDouble(flags.getOrDefault("bid-rate", "0.7"));

        StubBidder bidder = new StubBidder(port, delay, bidRate);
        Runtime.getRuntime().addShutdownHook(new Thread(bidder::close));
        System.out.printf("stub bidder on %s (delay %dms, bid rate %.2f). Ctrl+C to stop.%n",
                bidder.bidUri(), delay.toMillis(), bidRate);

        new CountDownLatch(1).await();
    }

    /** Parses "--name value" pairs that follow the command word. */
    static Map<String, String> parseFlags(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("expected --name value, got: " + arg);
            }
            flags.put(arg.substring(2), args[++i]);
        }
        return flags;
    }

    private static int intFlag(Map<String, String> flags, String name, int defaultValue) {
        return Integer.parseInt(flags.getOrDefault(name, String.valueOf(defaultValue)));
    }

    private static void printUsage() {
        System.err.println("""
                usage:
                  run  --target URL --qps N --duration SECONDS --warmup SECONDS --seed N --users N
                  stub --port N --delay-ms N --bid-rate 0.0-1.0""");
    }
}
