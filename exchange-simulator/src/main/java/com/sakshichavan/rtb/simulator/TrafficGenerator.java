package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.openrtb.AuctionType;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Device;
import com.sakshichavan.rtb.common.openrtb.Geo;
import com.sakshichavan.rtb.common.openrtb.Imp;
import com.sakshichavan.rtb.common.openrtb.Site;
import com.sakshichavan.rtb.common.openrtb.User;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Generates realistic OpenRTB bid requests.
 * Deterministic: the same seed always yields the same sequence, so load tests are reproducible.
 * Not thread-safe: create one generator per thread. The user sampler is immutable and can be shared.
 */
public final class TrafficGenerator {

    private static final WeightedSampler<Banner> AD_SIZES = new WeightedSampler<>(
            List.of(new Banner(300, 250), new Banner(728, 90), new Banner(320, 50),
                    new Banner(160, 600), new Banner(300, 600)),
            new double[] {40, 25, 20, 10, 5});

    private static final WeightedSampler<String> COUNTRIES = new WeightedSampler<>(
            List.of("USA", "GBR", "DEU", "CAN", "IND", "BRA"),
            new double[] {50, 15, 10, 10, 10, 5});

    private static final WeightedSampler<Site> SITES = new WeightedSampler<>(
            List.of(
                    new Site("site-news", "news.example.com", List.of("IAB12")),
                    new Site("site-sports", "sports.example.com", List.of("IAB17")),
                    new Site("site-tech", "tech.example.com", List.of("IAB19")),
                    new Site("site-food", "recipes.example.com", List.of("IAB8")),
                    new Site("site-travel", "travel.example.com", List.of("IAB20")),
                    new Site("site-auto", "cars.example.com", List.of("IAB2")),
                    new Site("site-finance", "money.example.com", List.of("IAB13")),
                    new Site("site-games", "games.example.com", List.of("IAB9", "IAB1"))),
            new double[] {30, 20, 15, 10, 10, 6, 5, 4});

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8)",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)");

    private static final int[] TMAX_OPTIONS = {80, 100, 120};
    private static final double ANONYMOUS_SHARE = 0.20;
    private static final double FIRST_PRICE_SHARE = 0.90;
    private static final double MIN_FLOOR = 0.10;
    private static final double MAX_FLOOR = 3.00;

    private final SplittableRandom random;
    private final WeightedSampler<Integer> users;
    private final String idPrefix;
    private long sequence;

    public TrafficGenerator(long seed, int generatorId, WeightedSampler<Integer> users) {
        this.random = new SplittableRandom(seed);
        this.users = users;
        this.idPrefix = "req-g" + generatorId + "-";
    }

    /** Builds the Zipf user distribution once; share the result across all generators. */
    public static WeightedSampler<Integer> userSampler(int userPoolSize) {
        return WeightedSampler.zipf(userPoolSize, 1.0);
    }

    public BidRequest next() {
        String requestId = idPrefix + sequence++;
        Imp imp = new Imp("1", AD_SIZES.sample(random), randomFloor(), "USD");
        Site site = SITES.sample(random);
        Device device = new Device(pick(USER_AGENTS), randomIp(), new Geo(COUNTRIES.sample(random), null));
        User user = random.nextDouble() < ANONYMOUS_SHARE ? null : new User("user-" + users.sample(random));
        int tmax = TMAX_OPTIONS[random.nextInt(TMAX_OPTIONS.length)];
        AuctionType auctionType = random.nextDouble() < FIRST_PRICE_SHARE
                ? AuctionType.FIRST_PRICE
                : AuctionType.SECOND_PRICE;

        return new BidRequest(requestId, List.of(imp), site, device, user, tmax, auctionType);
    }

    private double randomFloor() {
        double floor = MIN_FLOOR + random.nextDouble() * (MAX_FLOOR - MIN_FLOOR);
        return Math.round(floor * 100) / 100.0;
    }

    private String randomIp() {
        return random.nextInt(1, 224) + "." + random.nextInt(256) + "."
                + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    private <T> T pick(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }
}
