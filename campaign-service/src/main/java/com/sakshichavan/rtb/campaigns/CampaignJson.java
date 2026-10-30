package com.sakshichavan.rtb.campaigns;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;

/**
 * JSON for the campaign API. Unknown fields are rejected: in an admin API a misspelled field name ("dailyBudget")
 * must be an error, not a silently ignored change.
 */
final class CampaignJson {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private CampaignJson() {
    }

    static <T> T read(byte[] json, Class<T> type) throws JsonProcessingException {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static <T> T readStored(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored campaign is not valid JSON", e);
        }
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize " + value, e);
        }
    }

    static byte[] writeBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize " + value, e);
        }
    }
}
