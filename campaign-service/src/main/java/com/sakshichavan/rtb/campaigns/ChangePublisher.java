package com.sakshichavan.rtb.campaigns;

import java.util.List;

/** Delivers a batch of changes durably, in order; returns only once every change is confirmed. */
public interface ChangePublisher extends AutoCloseable {

    void publish(List<CampaignChange> changes) throws Exception;

    @Override
    default void close() {
    }
}
