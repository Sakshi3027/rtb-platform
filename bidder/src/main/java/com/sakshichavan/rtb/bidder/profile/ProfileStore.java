package com.sakshichavan.rtb.bidder.profile;

import java.util.concurrent.CompletionStage;

/** Looks up user profiles. Asynchronous: callers must never block an event loop waiting for the result. */
@FunctionalInterface
public interface ProfileStore extends AutoCloseable {

    CompletionStage<UserProfile> lookup(String userId);

    @Override
    default void close() {
    }
}
