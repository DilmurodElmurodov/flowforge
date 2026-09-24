package com.flowforge.modules.execution.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Custom Micrometer instruments of the execution runtime. Names follow Micrometer's dotted convention and are
 * rendered by the Prometheus registry as:
 * <ul>
 *   <li>{@code workflow_execution_duration_seconds} (timer, histogram enabled) tagged {@code workflow,status}</li>
 *   <li>{@code workflow_execution_failures_total} (counter) tagged {@code workflow,step_type,reason}</li>
 *   <li>{@code workflow_step_retries_total} (counter) tagged {@code step_type}</li>
 * </ul>
 */
@Component
public class ExecutionMetrics {

    public static final String DURATION = "workflow.execution.duration";
    public static final String FAILURES = "workflow.execution.failures";
    public static final String STEP_RETRIES = "workflow.step.retries";

    private final MeterRegistry registry;

    public ExecutionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCompletion(String workflow, String status, Duration duration) {
        Timer.builder(DURATION)
                .description("End-to-end duration of workflow executions")
                .tag("workflow", safe(workflow))
                .tag("status", status)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    public void recordFailure(String workflow, String stepType, String reason) {
        Counter.builder(FAILURES)
                .description("Workflow executions that ended in FAILED state")
                .tag("workflow", safe(workflow))
                .tag("step_type", stepType)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void recordStepRetry(String stepType) {
        Counter.builder(STEP_RETRIES)
                .description("Step attempts retried after a failure")
                .tag("step_type", stepType)
                .register(registry)
                .increment();
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value;
    }
}
