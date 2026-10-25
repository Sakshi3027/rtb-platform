package com.sakshichavan.rtb.bidder.budget;

import java.util.concurrent.CompletionStage;

/**
 * Shared store of campaign budgets that bidder instances lease chunks from.
 * All methods are asynchronous: they return immediately and never block the calling event loop thread.
 */
public interface BudgetSource extends AutoCloseable {

    /** Grants up to requestedMicros of the campaign's remaining budget. Completes with 0 when exhausted or unknown. */
    CompletionStage<Long> lease(String campaignId, long requestedMicros);

    /** Sets the campaign's budget for the current day only if it is not set yet. Completes true if this call set it. */
    CompletionStage<Boolean> initialize(String campaignId, long budgetMicros);

    @Override
    default void close() {
    }
}
