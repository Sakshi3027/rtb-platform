package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.audience.AudienceSegments;
import com.sakshichavan.rtb.common.features.ImpressionFeatures;
import com.sakshichavan.rtb.common.openrtb.BidRequest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Writes labeled click data for training: one row per impression, with a uniformly random campaign (like an
 * exploration slice of traffic, which avoids the selection bias of logging only the campaigns a policy chose).
 * true_ctr is the hidden ground truth, recorded for evaluation only; it must never be used as a feature.
 */
public final class DataGenerator {

    static final String HEADER = "campaign_id,size,country,category,segments,known_user,true_ctr,click";

    /**
     * oracleLogLoss: the log loss of predicting the true probabilities, the best any model can expect to achieve.
     * baselineLogLoss: the log loss of always predicting the overall click rate, the score of a model that learned nothing.
     */
    record Summary(long rows, long clicks, double meanTrueCtr, double oracleLogLoss, double baselineLogLoss) {

        double clickRate() {
            return (double) clicks / rows;
        }
    }

    private DataGenerator() {
    }

    static Summary generate(Writer out, long rows, long seed) throws IOException {
        TrafficGenerator traffic = new TrafficGenerator(seed, 0, TrafficGenerator.userSampler(100_000));
        SplittableRandom random = new SplittableRandom(seed + 1);
        List<String> campaigns = ClickModel.campaignIds();

        long clicks = 0;
        double sumTrueCtr = 0;
        double oracleLoss = 0;
        out.write(HEADER);
        out.write('\n');

        for (long row = 0; row < rows; row++) {
            BidRequest request = traffic.next();
            String campaignId = campaigns.get(random.nextInt(campaigns.size()));
            Set<String> segments = (request.user() == null) ? Set.of() : AudienceSegments.forUser(request.user().id());
            ImpressionFeatures features = ImpressionFeatures.of(request, request.imp().get(0), campaignId, segments);

            double trueCtr = ClickModel.probability(features);
            boolean clicked = random.nextDouble() < trueCtr;

            clicks += clicked ? 1 : 0;
            sumTrueCtr += trueCtr;
            oracleLoss += clicked ? -Math.log(trueCtr) : -Math.log(1 - trueCtr);

            out.write(String.join(",",
                    features.campaignId(),
                    features.size(),
                    features.country(),
                    features.category(),
                    String.join("|", features.segments()),
                    features.knownUser() ? "1" : "0",
                    String.format(Locale.ROOT, "%.6f", trueCtr),
                    clicked ? "1" : "0"));
            out.write('\n');
        }

        double rate = (double) clicks / rows;
        double baselineLoss = -(rate * Math.log(rate) + (1 - rate) * Math.log(1 - rate));
        return new Summary(rows, clicks, sumTrueCtr / rows, oracleLoss / rows, baselineLoss);
    }

    public static void main(String[] args) throws IOException {
        long rows = 1_000_000;
        long seed = 2026;
        Path out = Path.of("data/impressions.csv");
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--rows" -> rows = Long.parseLong(args[i + 1]);
                case "--seed" -> seed = Long.parseLong(args[i + 1]);
                case "--out" -> out = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown flag: " + args[i]);
            }
        }

        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Summary summary;
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            summary = generate(writer, rows, seed);
        }

        System.out.printf(Locale.ROOT, """
                        wrote %,d rows to %s
                        clicks           %,d (click rate %.4f%%, mean true CTR %.4f%%)
                        log loss         oracle %.5f | constant baseline %.5f
                        """,
                summary.rows(), out, summary.clicks(), 100 * summary.clickRate(), 100 * summary.meanTrueCtr(),
                summary.oracleLogLoss(), summary.baselineLogLoss());
    }
}
