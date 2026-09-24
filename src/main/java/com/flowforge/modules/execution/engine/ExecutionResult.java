package com.flowforge.modules.execution.engine;

import com.flowforge.modules.workflow.model.StepDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outcome reported by a {@link StepExecutor}.
 *
 * @param status  whether the step finished, parked the execution, or failed
 * @param outcome named outcome used to resolve the transition (e.g. {@code SUCCESS}, {@code APPROVED})
 * @param output  data made available to downstream steps under {@code steps.<id>.output}
 * @param message human-readable detail (failure reason, waiting reason)
 */
public record ExecutionResult(Status status, String outcome, Map<String, Object> output, String message) {

    public enum Status { COMPLETED, WAITING, FAILED }

    public ExecutionResult {
        output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
    }

    public static ExecutionResult success() {
        return new ExecutionResult(Status.COMPLETED, StepDefinition.OUTCOME_SUCCESS, Map.of(), null);
    }

    public static ExecutionResult success(Map<String, Object> output) {
        return new ExecutionResult(Status.COMPLETED, StepDefinition.OUTCOME_SUCCESS, output, null);
    }

    public static ExecutionResult completed(String outcome, Map<String, Object> output) {
        return new ExecutionResult(Status.COMPLETED, outcome, output, null);
    }

    public static ExecutionResult waiting(String message) {
        return new ExecutionResult(Status.WAITING, null, Map.of(), message);
    }

    public static ExecutionResult failure(String message) {
        return new ExecutionResult(Status.FAILED, "FAILURE", Map.of(), message);
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    public boolean isWaiting() {
        return status == Status.WAITING;
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }
}
