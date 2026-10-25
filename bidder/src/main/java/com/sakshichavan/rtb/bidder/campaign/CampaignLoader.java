package com.sakshichavan.rtb.bidder.campaign;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads campaign configuration from JSON. Strict on purpose: unknown fields fail loudly,
 * because a typo in config (e.g. "maxbid") must never be silently ignored.
 */
public final class CampaignLoader {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<Campaign>> CAMPAIGN_LIST = new TypeReference<>() {
    };

    private CampaignLoader() {
    }

    public static List<Campaign> fromClasspath(String resource) {
        try (InputStream in = CampaignLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("campaign resource not found: " + resource);
            }
            return read(in, resource);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + resource, e);
        }
    }

    public static List<Campaign> fromFile(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return read(in, path.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + path, e);
        }
    }

    private static List<Campaign> read(InputStream in, String source) throws IOException {
        List<Campaign> campaigns = MAPPER.readValue(in, CAMPAIGN_LIST);
        if (campaigns == null || campaigns.isEmpty()) {
            throw new IllegalArgumentException("no campaigns found in " + source);
        }
        return campaigns;
    }
}
