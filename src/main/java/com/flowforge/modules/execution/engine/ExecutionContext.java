package com.flowforge.modules.execution.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mutable working memory of one execution, handed to every {@link StepExecutor}.
 *
 * <p>Persisted as the {@code context_data} JSONB column with the shape
 * <pre>{@code { "input": {...}, "variables": {...}, "steps": { "<stepId>": { "output": {...} } } } }</pre>
 * Steps read upstream data through {@link #lookup(String)} using dotted paths such as
 * {@code input.customer.email} or {@code steps.review.output.decision}.
 */
public final class ExecutionContext {

    public static final String KEY_INPUT = "input";
    public static final String KEY_VARIABLES = "variables";
    public static final String KEY_STEPS = "steps";
    public static final String KEY_OUTPUT = "output";

    private final UUID executionId;
    private final UUID tenantId;
    private final Map<String, Object> input;
    private final Map<String, Object> variables;
    private final Map<String, Map<String, Object>> stepOutputs;

    private ExecutionContext(UUID executionId, UUID tenantId, Map<String, Object> input,
                             Map<String, Object> variables, Map<String, Map<String, Object>> stepOutputs) {
        this.executionId = executionId;
        this.tenantId = tenantId;
        this.input = Collections.unmodifiableMap(new LinkedHashMap<>(input));
        this.variables = new LinkedHashMap<>(variables);
        this.stepOutputs = new LinkedHashMap<>(stepOutputs);
    }

    public static ExecutionContext initial(UUID executionId, UUID tenantId, Map<String, Object> input) {
        return new ExecutionContext(executionId, tenantId, input == null ? Map.of() : input, Map.of(), Map.of());
    }

    @SuppressWarnings("unchecked")
    public static ExecutionContext fromPersistent(UUID executionId, UUID tenantId, Map<String, Object> data) {
        Map<String, Object> input = (Map<String, Object>) data.getOrDefault(KEY_INPUT, Map.of());
        Map<String, Object> variables = (Map<String, Object>) data.getOrDefault(KEY_VARIABLES, Map.of());
        Map<String, Map<String, Object>> steps = new LinkedHashMap<>();
        Map<String, Object> rawSteps = (Map<String, Object>) data.getOrDefault(KEY_STEPS, Map.of());
        rawSteps.forEach((stepId, value) -> {
            Map<String, Object> entry = (Map<String, Object>) value;
            steps.put(stepId, (Map<String, Object>) entry.getOrDefault(KEY_OUTPUT, Map.of()));
        });
        return new ExecutionContext(executionId, tenantId, input, variables, steps);
    }

    /** Snapshot suitable for the JSONB column. Always returns a fresh, mutable copy. */
    public Map<String, Object> toPersistent() {
        Map<String, Object> steps = new LinkedHashMap<>();
        stepOutputs.forEach((stepId, output) -> steps.put(stepId, Map.of(KEY_OUTPUT, new LinkedHashMap<>(output))));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_INPUT, new LinkedHashMap<>(input));
        data.put(KEY_VARIABLES, new LinkedHashMap<>(variables));
        data.put(KEY_STEPS, steps);
        return data;
    }

    public static Map<String, Object> initialPersistent(Map<String, Object> input) {
        return initial(null, null, input).toPersistent();
    }

    public void recordStepOutput(String stepId, Map<String, Object> output) {
        stepOutputs.put(stepId, output == null ? Map.of() : new LinkedHashMap<>(output));
    }

    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    public Map<String, Object> stepOutput(String stepId) {
        return stepOutputs.getOrDefault(stepId, Map.of());
    }

    /** Resolves a dotted path against {@code input}, {@code variables} or {@code steps}. */
    public Optional<Object> lookup(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        Object current = toPersistent();
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return Optional.empty();
            }
            current = map.get(segment);
        }
        return Optional.ofNullable(current);
    }

    public UUID executionId() {
        return executionId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public Map<String, Object> input() {
        return input;
    }

    public Map<String, Object> variables() {
        return Collections.unmodifiableMap(variables);
    }
}
