package com.sakshichavan.rtb.bidder.resilience;

/**
 * admitted: processed by a worker. shedQueueFull: rejected on arrival because the queue was full.
 * shedStale: dropped by a worker because it had already waited too long to meet the deadline.
 */
public record AdmissionStats(long admitted, long shedQueueFull, long shedStale, int queueDepth) {
}
