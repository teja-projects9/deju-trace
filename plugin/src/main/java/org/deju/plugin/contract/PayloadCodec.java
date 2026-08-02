package org.deju.plugin.contract;

import java.io.IOException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Single Jackson entry point for the plugin. Uses a plain {@link ObjectMapper} with
 * default/polymorphic typing left disabled (Jackson's default), payloads are treated
 * as untrusted data and never drive type instantiation.
 */
public final class PayloadCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PayloadCodec() {
    }

    public static DejuPayload parse(String json) throws IOException {
        return MAPPER.readValue(json, DejuPayload.class);
    }

    public static DejuPayload parse(byte[] json) throws IOException {
        return MAPPER.readValue(json, DejuPayload.class);
    }

    public static String toJson(DejuPayload payload) throws IOException {
        return MAPPER.writeValueAsString(payload);
    }
}
