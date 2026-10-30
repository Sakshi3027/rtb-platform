package com.sakshichavan.rtb.common.campaigns;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignSpecTest {

    private static CampaignSpec sports() {
        return new CampaignSpec("c-sports-us", "cr-sports-1", 2.80, 40.00,
                new TargetingSpec(List.of(new SizeSpec(300, 250)), List.of("USA"), List.of("IAB17"), List.of()),
                new FrequencyCapSpec(5, 3600), CampaignStatus.ACTIVE);
    }

    @Test
    void aCompleteCampaignHasNoProblems() {
        assertEquals(List.of(), sports().problems());
    }

    @Test
    void reportsEveryProblemAtOnce() {
        CampaignSpec bad = new CampaignSpec("X", " ", -1, 0,
                new TargetingSpec(List.of(new SizeSpec(0, 250)), List.of("us"), List.of("sports"), List.of("Sports Fan")),
                new FrequencyCapSpec(0, 5), null);

        List<String> problems = bad.problems();

        assertEquals(10, problems.size(), problems.toString());
    }

    @Test
    void missingStatusAndTargetingGetSafeDefaults() {
        CampaignSpec minimal = new CampaignSpec("c-minimal", "cr-1", 1.0, 10.0, null, null, null);

        assertEquals(CampaignStatus.ACTIVE, minimal.status());
        assertTrue(minimal.targeting().countries().isEmpty(), "no targeting means any country");
        assertEquals(List.of(), minimal.problems());
    }
}
