package com.flowforge.modules.workflow.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One node of the workflow graph, as declared in the JSON definition:
 * <pre>{@code
 * {
 *   "id": "manager-review",
 *   "name": "Manager review",
 *   "type": "APPROVAL",
 *   "config": { "approverRole": "MANAGER" },
 *   "transitions": { "APPROVED": "notify-requester", "REJECTED": "END" },
 *   "retry": { "maxAttempts": 3, "backoffMillis": 500 }
 * }
 * }</pre>
 * {@code transitions} maps a step outcome to the id of the next step, or to {@value #END} to complete the
 * execution. When no explicit transition exists for the {@code SUCCESS} outcome the engine advances to the next
 * step in declaration order.
 */
public record StepDefinition(
        String id,
        String name,
        StepType type,
        Map<String, Object> config,
        Map<String, String> transitions,
        RetryPolicy retry) {

    public static final String END = "END";
    public static final String OUTCOME_SUCCESS = "SUCCESS";

    public StepDefinition {
        config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
        transitions = transitions == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(transitions));
        retry = retry == null ? RetryPolicy.NONE : retry;
        name = name == null || name.isBlank() ? id : name;
    }

    public Optional<String> transitionFor(String outcome) {
        return Optional.ofNullable(transitions.get(outcome));
    }

    /** Typed access to a configuration value with a default. */
    @SuppressWarnings("unchecked")
    public <T> T configValue(String key, T defaultValue) {
        Object value = config.get(key);
        return value == null ? defaultValue : (T) value;
    }

    public String requiredConfig(String key) {
        Object value = config.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Step '" + id + "' is missing required config '" + key + "'");
        }
        return value.toString();
    }
}
