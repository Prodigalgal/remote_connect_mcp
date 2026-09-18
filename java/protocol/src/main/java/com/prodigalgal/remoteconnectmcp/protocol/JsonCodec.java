package com.prodigalgal.remoteconnectmcp.protocol;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Shared Jackson 3 codec for the current Remote Connect wire contract. */
public final class JsonCodec {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private JsonCodec() {
    }

    public static byte[] write(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalArgumentException("cannot encode protocol JSON", exception);
        }
    }

    public static <T> T read(byte[] data, Class<T> type) {
        try {
            return MAPPER.readValue(data, type);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalArgumentException("cannot decode protocol JSON", exception);
        }
    }
}
