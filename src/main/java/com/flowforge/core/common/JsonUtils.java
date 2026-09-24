package com.flowforge.core.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Centralised JSON (de)serialisation helper.
 *
 * <p>Uses a dedicated, immutable {@link ObjectMapper} so infrastructure code (outbox payloads, idempotency cache,
 * Kafka envelopes, JSONB columns) is not affected by web-layer customisations of the Spring-managed mapper.
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            // Same mix-in Spring MVC uses so problem responses written from filters look identical to MVC ones.
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new JsonException("Failed to serialise " + value.getClass().getSimpleName() + " to JSON", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new JsonException("Failed to deserialise JSON into " + type.getSimpleName(), e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new JsonException("Failed to deserialise JSON into " + type.getType(), e);
        }
    }

    public static Object fromJson(String json, Type type) {
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructType(type));
        } catch (JsonProcessingException e) {
            throw new JsonException("Failed to deserialise JSON into " + type, e);
        }
    }

    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new JsonException("Malformed JSON document", e);
        }
    }

    /** Converts any POJO/record into a generic map (used for JSONB columns and outbox payloads). */
    public static Map<String, Object> toMap(Object value) {
        return MAPPER.convertValue(value, MAP_TYPE);
    }

    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    /** Unchecked wrapper so callers are not forced to handle Jackson's checked exceptions. */
    public static class JsonException extends RuntimeException {
        public JsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
