package com.flowforge.core.common;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helper for JSONB columns mapped as {@code String} + {@code @JdbcTypeCode(SqlTypes.JSON)}.
 *
 * <p>Generic {@code Map<String, Object>} JSON attributes were observed to come back from Hibernate 6.6 with nested
 * arrays materialised as objects. Mapping the column as a raw JSON string and converting with Jackson keeps the
 * document byte-for-byte faithful while entities still expose a typed {@code Map} API.
 */
public final class JsonDocument {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private JsonDocument() {
    }

    public static String write(Map<String, Object> value) {
        return JsonUtils.toJson(value == null ? Map.of() : value);
    }

    public static Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return JsonUtils.fromJson(json, MAP);
    }
}
