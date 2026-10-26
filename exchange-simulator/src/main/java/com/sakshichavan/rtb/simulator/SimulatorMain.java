package com.sakshichavan.rtb.simulator;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Command-line entry point.
 *   run  : drive open-loop load at one or more bidders and print a latency and frequency report
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
        List<URI> targets = flags.containsKey("targets")
                ? Arrays.stream(flags.get("targets").split(",")).map(String::trim).map(URI::create).toList()
                : List.of(URI.create(flags.getOrDefault("target", "http://127.0.0.1:8080/bid")));
        String routing = flags.getOrDefault("routing", "round-robin");
        int qps = intFlag(flags, "qps", 1_000);
        int durationSeconds = intFlag(flags, "duration", 10);
        int warmupSeconds = intFlag(flags, "warmup", 3);
        long seed = Long.parseLong(flags.getOrDefault("seed", "42"));
        int users = intFlag(flags, "users", 100_000);
        Map<String, Integer> caps = parseCaps(flags.get("caps"));

        WeightedSampler<Integer> userSampler = TrafficGenerator.userSampler(users);
        Router router = Router.of(routing, targets);

        try (LoadDriver driver = new LoadDriver(router, null)) {
            if (warmupSeconds > 0) {
                System.out.printf("warming up: %ds at %d req/s (results discarded)%n", warmupSeconds, qps);
                driver.run(new TrafficGenerator(seed + 1, 1, userSampler), qps, Duration.ofSeconds(warmupSeconds));
            }
            System.out.printf("measuring: %ds at %d req/s, routing %s across %d target(s)%n",
                    durationSeconds, qps, routing, targets.size());
            LoadReport report = driver.run(
                    new TrafficGenerator(seed, 0, userSampler), qps, Duration.ofSeconds(durationSeconds), caps);
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

    /** Parses "campaignA=3,campaignB=5" into an ordered map. Null or blank means audit nothing. */
    static Map<String, Integer> parseCaps(String spec) {
        Map<String, Integer> caps = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return caps;
        }
        for (String entry : spec.split(",")) {
            String[] parts = entry.trim().split("=");
            if (parts.length != 2) {
                throw new IllegalArgumentException("expected campaign=cap, got: " + entry);
            }
            caps.put(parts[0].trim(), Integer.parseInt(parts[1].trim()));
        }
        return caps;
    }

    private static int intFlag(Map<String, String> flags, String name, int defaultValue) {
        return Integer.parseInt(flags.getOrDefault(name, String.valueOf(defaultValue)));
    }

    private static void printUsage() {
        System.err.println("""
                usage:
                  run  --target URL | --targets URL1,URL2,...  --routing round-robin|user-hash
                       --qps N --duration SECONDS --warmup SECONDS --seed N --users N
                       --caps campaignA=3,campaignB=5
                  stub --port N --delay-ms N --bid-rate 0.0-1.0""");
    }
}
