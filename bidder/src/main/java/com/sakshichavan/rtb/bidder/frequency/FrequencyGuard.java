package com.sakshichavan.rtb.bidder.frequency;

import com.sakshichavan.rtb.bidder.campaign.Campaign;

/** Decides whether a campaign may show to a user again. Implementations must be fast and non-blocking. */
public interface FrequencyGuard {

    /** Takes one impression slot for this user and campaign if the cap allows; false means capped. */
    boolean tryAcquire(String userId, Campaign campaign);

    /** Gives back a slot taken by tryAcquire, e.g. when the bid could not be placed after all. */
    void release(String userId, Campaign campaign);

    /** Never caps. For tests and tools that do not exercise frequency caps. */
    FrequencyGuard UNLIMITED = new FrequencyGuard() {
        @Override
        public boolean tryAcquire(String userId, Campaign campaign) {
            return true;
        }

        @Override
        public void release(String userId, Campaign campaign) {
        }
    };
}
