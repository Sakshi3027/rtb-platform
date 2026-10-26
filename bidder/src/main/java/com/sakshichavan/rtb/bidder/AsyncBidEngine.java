package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** A bid decision that may involve I/O. Returns immediately; the result completes later. */
@FunctionalInterface
public interface AsyncBidEngine {

    CompletionStage<Optional<BidResponse>> evaluate(BidRequest request);

    /** Adapts a synchronous engine. Exceptions become failed futures instead of escaping. */
    static AsyncBidEngine of(BidEngine engine) {
        return request -> {
            try {
                return CompletableFuture.completedFuture(engine.evaluate(request));
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        };
    }
}
