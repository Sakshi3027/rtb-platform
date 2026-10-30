package com.sakshichavan.rtb.bidder.profile;

import com.sakshichavan.rtb.common.audience.AudienceSegments;

import java.util.concurrent.CompletableFuture;

/**
 * Deterministic fake audience data: the same user id always gets the same segments.
 * Used when no real profile database is configured, and to seed one for testing.
 * Segments come from AudienceSegments, the definition the simulator's click model also uses.
 */
public final class SyntheticProfiles {

    private SyntheticProfiles() {
    }

    public static UserProfile forUser(String userId) {
        return new UserProfile(AudienceSegments.forUser(userId));
    }

    /** An in-process store that answers instantly from the synthetic data. */
    public static ProfileStore store() {
        return userId -> CompletableFuture.completedFuture(forUser(userId));
    }
}
