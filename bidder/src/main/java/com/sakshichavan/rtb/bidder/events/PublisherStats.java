package com.sakshichavan.rtb.bidder.events;

/** published: handed to the producer. acked: confirmed written by Kafka. failed: rejected or not confirmed. */
public record PublisherStats(long published, long acked, long failed) {
}
