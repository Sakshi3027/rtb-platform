package com.sakshichavan.rtb.simulator;

/**
 * The exchange's view of the auctions in one run. silentLosses are losses that got no notice;
 * the bidder must release those reservations on its own when they expire.
 * expectedClicks is the sum of the true click probabilities of won impressions: the same quantity as clicks,
 * without the coin-flip noise, so comparisons between runs need far less traffic.
 */
public record MarketSummary(long won, long lost, long silentLosses, long winNotices, long lossNotices,
                            long duplicateNotices, long noticeFailures, long spendMicros,
                            long clicks, double expectedClicks) {

    String format() {
        double winRate = (won + lost == 0) ? 0 : 100.0 * won / (won + lost);
        double averageClearingCpm = (won == 0) ? 0 : spendMicros / (double) won / 1_000.0;
        double spendDollars = spendMicros / 1_000_000.0;
        double costPerExpectedClick = (expectedClicks == 0) ? 0 : spendDollars / expectedClicks;
        double expectedClicksPerDollar = (spendDollars == 0) ? 0 : expectedClicks / spendDollars;
        return """
                auction      won %d | lost %d | win rate %.1f%% | spend %d micros ($%.4f) | avg clearing CPM $%.2f
                notices      win %d | loss %d | duplicates %d | silent losses %d | failed %d
                clicks       %d (expected %.1f) | cost per expected click $%.3f | expected clicks per dollar %.2f"""
                .formatted(won, lost, winRate, spendMicros, spendDollars, averageClearingCpm,
                        winNotices, lossNotices, duplicateNotices, silentLosses, noticeFailures,
                        clicks, expectedClicks, costPerExpectedClick, expectedClicksPerDollar);
    }
}
