package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.budget.BudgetSource;
import com.sakshichavan.rtb.bidder.budget.CampaignBudgets;
import com.sakshichavan.rtb.bidder.budget.InMemoryBudgetSource;
import com.sakshichavan.rtb.bidder.budget.PacingPolicy;
import com.sakshichavan.rtb.bidder.budget.RedisBudgetSource;
import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.CampaignLoader;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;
import com.sakshichavan.rtb.bidder.frequency.FrequencyCapper;
import com.sakshichavan.rtb.common.money.Micros;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Starts the bidder. Configuration comes from environment variables (container-friendly). */
public final class BidderMain {

    private BidderMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        int port = Integer.parseInt(System.getenv().getOrDefault("BIDDER_PORT", "8080"));
        double shadingFactor = Double.parseDouble(System.getenv().getOrDefault("BID_SHADING_FACTOR", "0.5"));
        long leaseChunkMicros = Micros.fromDollars(
                Double.parseDouble(System.getenv().getOrDefault("LEASE_CHUNK_USD", "0.50")));
        String pacingMode = System.getenv().getOrDefault("PACING", "even");
        double pacingBurst = Double.parseDouble(System.getenv().getOrDefault("PACING_BURST", "0.02"));
        long maxCappedUsers = Long.parseLong(System.getenv().getOrDefault("FREQ_CAP_MAX_USERS", "1000000"));
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

        Duration longestCapWindow = campaigns.stream()
                .map(Campaign::frequencyCap)
                .filter(Objects::nonNull)
                .map(cap -> Duration.ofSeconds(cap.windowSeconds()))
                .max(Comparator.naturalOrder())
                .orElse(Duration.ofHours(1));
        FrequencyCapper frequencyCapper = new FrequencyCapper(maxCappedUsers, longestCapWindow, System::nanoTime);

        BidEngine engine = new TargetingBidEngine(index, new BidPricer(shadingFactor), budgets, frequencyCapper);
        BidderServer server = new BidderServer(port, engine);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            budgetSource.close();
        }));

        System.out.printf("bidder listening on port %d with %d campaigns, shading %.2f, pacing %s, budgets: %s%n",
                server.port(), index.size(), shadingFactor,
                "none".equals(pacingMode) ? "none" : "even (burst " + pacingBurst + ")",
                (redisUri == null) ? "in-memory (single instance only)" : "redis " + redisUri);
    }
}
