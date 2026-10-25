package com.sakshichavan.rtb.bidder.campaign;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CampaignLoaderTest {

    @Test
    void loadsBundledCampaignsWithUniqueIds() {
        List<Campaign> campaigns = CampaignLoader.fromClasspath("/campaigns.json");

        Set<String> ids = campaigns.stream().map(Campaign::id).collect(Collectors.toSet());
        assertEquals(10, campaigns.size());
        assertEquals(campaigns.size(), ids.size());
    }

    @Test
    void rejectsConfigWithUnknownField(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("campaigns.json");
        Files.writeString(file, """
                [{"id": "c1", "creativeId": "cr-1", "maxbid": 2.0}]
                """);

        assertThrows(UncheckedIOException.class, () -> CampaignLoader.fromFile(file));
    }
}
