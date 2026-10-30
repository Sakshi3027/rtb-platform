package com.sakshichavan.rtb.common.events;

/**
 * The outcome of one bid's budget reservation. eventId is derived from the bid id, so any re-send of the same
 * settlement carries the same id and consumers can deduplicate. chargedMicros is non-zero only for wins.
 */
public record SettlementEvent(
        String eventId,
        SettlementType type,
        String bidId,
        String campaignId,
        long reservedMicros,
        long chargedMicros,
        long occurredAtMillis) {

    public static String eventIdFor(String bidId) {
        return "settlement-" + bidId;
    }

    public static SettlementEvent win(String bidId, String campaignId, long reservedMicros, long chargedMicros,
                                      long occurredAtMillis) {
        return new SettlementEvent(eventIdFor(bidId), SettlementType.WIN, bidId, campaignId,
                reservedMicros, chargedMicros, occurredAtMillis);
    }

    public static SettlementEvent loss(String bidId, String campaignId, long reservedMicros, long occurredAtMillis) {
        return new SettlementEvent(eventIdFor(bidId), SettlementType.LOSS, bidId, campaignId,
                reservedMicros, 0, occurredAtMillis);
    }

    public static SettlementEvent expired(String bidId, String campaignId, long reservedMicros, long occurredAtMillis) {
        return new SettlementEvent(eventIdFor(bidId), SettlementType.EXPIRED, bidId, campaignId,
                reservedMicros, 0, occurredAtMillis);
    }
}
