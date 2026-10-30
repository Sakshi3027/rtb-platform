package com.sakshichavan.rtb.simulator;

/**
 * The exchange's view of the auctions in one run. silentLosses are losses that got no notice;
 * the bidder must release those reservations on its own when they expire.
 */
public record MarketSummary(long won, long lost, long silentLosses, long winNotices, long lossNotices,
                            long duplicateNotices, long noticeFailures, long spendMicros) {

    String format() {
        double winRate = (won + lost == 0) ? 0 : 100.0 * won / (won + lost);
        double averageClearingCpm = (won == 0) ? 0 : spendMicros / (double) won / 1_000.0;
        return """
                auction      won %d | lost %d | win rate %.1f%% | spend %d micros ($%.4f) | avg clearing CPM $%.2f
                notices      win %d | loss %d | duplicates %d | silent losses %d | failed %d"""
                .formatted(won, lost, winRate, spendMicros, spendMicros / 1_000_000.0, averageClearingCpm,
                        winNotices, lossNotices, duplicateNotices, silentLosses, noticeFailures);
    }
}
