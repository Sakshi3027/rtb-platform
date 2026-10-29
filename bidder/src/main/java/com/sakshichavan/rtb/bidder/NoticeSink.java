package com.sakshichavan.rtb.bidder;

/** Receives auction outcome notices from the exchange. Implementations must be idempotent: notices can repeat. */
public interface NoticeSink {

    void win(String bidId, double clearingPriceCpm);

    void loss(String bidId);

    NoticeSink NONE = new NoticeSink() {
        @Override
        public void win(String bidId, double clearingPriceCpm) {
        }

        @Override
        public void loss(String bidId) {
        }
    };
}
