package com.sakshichavan.rtb.simulator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrequencyAuditTest {

    @Test
    void summarizesPerUserExposureForAuditedCampaignsOnly() {
        FrequencyAudit audit = new FrequencyAudit();
        audit.record("u1", "c1");
        audit.record("u1", "c1");
        audit.record("u1", "c1");
        audit.record("u2", "c1");
        audit.record("u1", "uncapped");

        List<CampaignExposure> exposures = audit.summarize(Map.of("c1", 2));

        assertEquals(List.of(new CampaignExposure("c1", 2, 2, 3, 1)), exposures);
    }
}
