package com.sakshichavan.rtb.bidder.profile;

import java.util.Set;

/** Audience data for one user, such as "auto-intender" or "traveler". UNKNOWN means no data was available. */
public record UserProfile(Set<String> segments) {

    public static final UserProfile UNKNOWN = new UserProfile(Set.of());

    public UserProfile {
        segments = (segments == null) ? Set.of() : Set.copyOf(segments);
    }
}
