package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.budget.BudgetSource;
import com.sakshichavan.rtb.bidder.budget.CampaignBudgets;
import com.sakshichavan.rtb.bidder.budget.InMemoryBudgetSource;
import com.sakshichavan.rtb.bidder.budget.PacingPolicy;
import com.sakshichavan.rtb.bidder.budget.RedisBudgetSource;
import com.sakshichavan.rtb.bidder.budget.ReservationLedger;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.CampaignLoader;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.bidder.events.KafkaSettlementPublisher;
import com.sakshichavan.rtb.bidder.events.SettlementPublisher;
import com.sakshichavan.rtb.bidder.frequency.FrequencyCapper;
import com.sakshichavan.rtb.bidder.profile.AerospikeProfileStore;
import com.sakshichavan.rtb.bidder.profile.ProfileStore;
import com.sakshichavan.rtb.bidder.profile.SyntheticProfiles;
import com.sakshichavan.rtb.bidder.resilience.AdmissionController;
import com.sakshichavan.rtb.common.money.Micros;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Starts the bidder. Configuration comes from environment variables (container-friendly). */
public final class BidderMain {

    private BidderMain() {
    }

    private static String env(String name, String defaultValue) {
        return System.getenv().getOrDefault(name, defaultValue);
    }

    public static void main(String[] args) throws InterruptedException {
        int port = Integer.parseInt(env("BIDDER_PORT", "8080"));
        String publicUrl = env("BIDDER_PUBLIC_URL", "http://127.0.0.1:" + port);
        double shadingFactor = Double.parseDouble(env("BID_SHADING_FACTOR", "0.5"));
        long leaseChunkMicros = Micros.fromDollars(Double.parseDouble(env("LEASE_CHUNK_USD", "0.50")));
        String pacingMode = env("PACING", "even");
        double pacingBurst = Double.parseDouble(env("PACING_BURST", "0.02"));
        long maxCappedUsers = Long.parseLong(env("FREQ_CAP_MAX_USERS", "1000000"));
        long profileTimeoutMillis = Long.parseLong(env("PROFILE_TIMEOUT_MS", "5"));
        String profileMode = env("PROFILE_STORE", "synthetic");
        long reservationTtlSeconds = Long.parseLong(env("RESERVATION_TTL_SECONDS", "60"));
        boolean shedding = !"off".equals(env("SHEDDING", "on"));
        int ioThreads = Integer.parseInt(env("BIDDER_IO_THREADS", "0"));
        int workerThreads = Integer.parseInt(env("WORKER_THREADS",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        int queueCapacity = Integer.parseInt(env("QUEUE_CAPACITY", "2000"));
        long maxQueueDelayMillis = Long.parseLong(env("MAX_QUEUE_DELAY_MS", "20"));
        long simulatedWorkMicros = Long.parseLong(env("SIMULATED_WORK_MICROS", "0"));
        String settlementsTopic = env("SETTLEMENTS_TOPIC", "rtb.settlements");
        String kafkaBootstrap = System.getenv("KAFKA_BOOTSTRAP");
        String campaignsFile = System.getenv("CAMPAIGNS_FILE");
        String redisUri = System.getenv("REDIS_URI");

        List<Campaign> campaigns = (campaignsFile == null)
                ? CampaignLoader.fromClasspath("/campaigns.json")
                : CampaignLoader.fromFile(Path.of(campaignsFile));
        TargetingIndex index = new TargetingIndex(campaigns);

        PacingPolicy pacing = "none".equals(pacingMode) ? PacingPolicy.NONE : PacingPolicy.even(pacingBurst);
        Clock clock = Clock.systemUTC();
        BudgetSource budgetSource = (redisUri == null)
                ? new InMemoryBudgetSource(clock, pacing)
                : new RedisBudgetSource(redisUri, clock, pacing);
        CampaignBudgets budgets = new CampaignBudgets(budgetSource, leaseChunkMicros, leaseChunkMicros / 5, System::nanoTime);
        budgets.initialize(campaigns).toCompletableFuture().join();

        KafkaSettlementPublisher kafka = (kafkaBootstrap == null)
                ? null
                : new KafkaSettlementPublisher(kafkaBootstrap, settlementsTopic);
        if (kafka != null) {
            kafka.awaitReady(Duration.ofSeconds(30));
        }
        SettlementPublisher publisher = (kafka != null) ? kafka : SettlementPublisher.NONE;
        ReservationLedger ledger = new ReservationLedger(
                budgets, publicUrl, Duration.ofSeconds(reservationTtlSeconds), System::nanoTime, publisher);

        Duration longestCapWindow = campaigns.stream()
                .map(Campaign::frequencyCap)
                .filter(Objects::nonNull)
                .map(cap -> Duration.ofSeconds(cap.windowSeconds()))
                .max(Comparator.naturalOrder())
                .orElse(Duration.ofHours(1));
        FrequencyCapper frequencyCapper = new FrequencyCapper(maxCappedUsers, longestCapWindow, System::nanoTime);

        ProfileStore profileStore = "aerospike".equals(profileMode)
                ? AerospikeProfileStore.connectWithRetry(
                        env("AEROSPIKE_HOST", "localhost"),
                        Integer.parseInt(env("AEROSPIKE_PORT", "3000")),
                        env("AEROSPIKE_NAMESPACE", "test"),
                        (int) profileTimeoutMillis + 2, Duration.ofSeconds(30))
                : SyntheticProfiles.store();
        TargetingBidEngine engine = new TargetingBidEngine(
                index, new BidPricer(shadingFactor), budgets, frequencyCapper, ledger);
        BidPipeline pipeline = new BidPipeline(profileStore, engine, profileTimeoutMillis);

        AsyncBidEngine work = (simulatedWorkMicros > 0) ? SimulatedWork.wrap(pipeline, simulatedWorkMicros) : pipeline;
        AdmissionController admission = shedding
                ? new AdmissionController(work, workerThreads, queueCapacity, Duration.ofMillis(maxQueueDelayMillis))
                : null;
        AsyncBidEngine frontDoor = shedding ? admission : work;

        BidderServer server = BidderServer.withAsyncEngine(port, ioThreads, frontDoor, ledger, () -> {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("leaseChunkMicros", budgets.chunkMicros());
            stats.put("lowWaterMicros", budgets.lowWaterMicros());
            stats.put("campaigns", budgets.stats());
            stats.put("profiles", pipeline.stats());
            stats.put("notices", ledger.stats());
            if (kafka != null) {
                stats.put("events", kafka.stats());
            }
            if (admission != null) {
                stats.put("admission", admission.stats());
            }
            return stats;
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            if (admission != null) {
                admission.close();
            }
            pipeline.close();
            profileStore.close();
            if (kafka != null) {
                kafka.close();
            }
            budgetSource.close();
        }));

        System.out.printf("bidder listening on port %d with %d campaigns, pacing %s, profiles: %s (timeout %dms), "
                        + "budgets: %s, shedding: %s, io threads: %s, notices at %s, events: %s%s%n",
                server.port(), index.size(),
                "none".equals(pacingMode) ? "none" : "even (burst " + pacingBurst + ")",
                profileMode, profileTimeoutMillis,
                (redisUri == null) ? "in-memory" : "redis",
                shedding ? "on (" + workerThreads + " workers, max queue delay " + maxQueueDelayMillis + "ms)" : "off",
                (ioThreads == 0) ? "default" : String.valueOf(ioThreads),
                publicUrl,
                (kafka == null) ? "off" : "kafka " + kafkaBootstrap + " topic " + settlementsTopic,
                (simulatedWorkMicros > 0) ? ", simulated work " + simulatedWorkMicros + "us/bid" : "");
    }
}
