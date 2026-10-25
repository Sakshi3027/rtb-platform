package com.sakshichavan.rtb.common.openrtb;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Converts OpenRTB models to and from JSON bytes.
 * The ObjectMapper is created once and shared: it is expensive to build and thread-safe once configured.
 */
public final class OpenRtbJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private OpenRtbJson() {
    }

    public static byte[] write(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to serialize " + value.getClass().getSimpleName(), e);
        }
    }

    public static BidRequest readRequest(byte[] json) {
        return read(json, BidRequest.class);
    }

    public static BidResponse readResponse(byte[] json) {
        return read(json, BidResponse.class);
    }

    private static <T> T read(byte[] json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException("invalid " + type.getSimpleName() + " JSON", e);
        }
    }
}
