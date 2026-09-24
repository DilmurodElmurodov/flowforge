package com.flowforge.modules.execution.engine;

import com.flowforge.core.tenant.TenantContext;
import com.flowforge.modules.execution.metrics.ExecutionMetrics;
import com.flowforge.modules.execution.service.ExecutionSnapshot;
import com.flowforge.modules.execution.service.ExecutionStateManager;
import com.flowforge.modules.execution.service.StepAttempt;
import com.flowforge.modules.workflow.model.RetryPolicy;
import com.flowforge.modules.workflow.model.StepDefinition;
import com.flowforge.modules.workflow.model.StepType;
import com.flowforge.modules.workflow.model.Transition;
import com.flowforge.modules.workflow.model.WorkflowDefinition;
import com.flowforge.modules.workflow.model.WorkflowDefinitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * State-machine runtime for workflow executions.
 *
 * <p>Callers never block: {@link #execute} and {@link #resume} hand the run loop to the {@code workflowExecutor}
 * pool. Inside the loop, steps run outside any database transaction and every transition is persisted by
 * {@link ExecutionStateManager}, which also appends the matching outbox event in the same commit.
 */
@Component
public class WorkflowExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionEngine.class);

    private final Map<StepType, StepExecutor> executors;
    private final ExecutionStateManager state;
    private final ExecutionMetrics metrics;
    private final Executor workerPool;
    private final Clock clock;

    @Autowired
    public WorkflowExecutionEngine(Map<StepType, StepExecutor> executors, ExecutionStateManager state,
                                   ExecutionMetrics metrics, @Qualifier("workflowExecutor") Executor workerPool) {
        this(executors, state, metrics, workerPool, Clock.systemUTC());
    }

    public WorkflowExecutionEngine(Map<StepType, StepExecutor> executors, ExecutionStateManager state,
                                   ExecutionMetrics metrics, Executor workerPool, Clock clock) {
        this.executors = Map.copyOf(executors);
        this.state = state;
        this.metrics = metrics;
        this.workerPool = workerPool;
        this.clock = clock;
    }

    /** Starts a PENDING execution on the worker pool. */
    public CompletableFuture<Void> execute(UUID executionId, UUID tenantId) {
        return CompletableFuture.runAsync(() -> TenantContext.runWith(tenantId, () -> run(executionId, null)), workerPool);
    }

    /** Continues a parked execution, feeding {@code outcome} to the current step instead of its executor. */
    public CompletableFuture<Void> resume(UUID executionId, UUID tenantId, String outcome) {
        return CompletableFuture.runAsync(() -> TenantContext.runWith(tenantId, () -> run(executionId, outcome)), workerPool);
    }

    void run(UUID executionId, String resumeOutcome) {
        ExecutionSnapshot snapshot;
        try {
            snapshot = resumeOutcome == null ? state.begin(executionId) : state.snapshot(executionId);
        } catch (RuntimeException e) {
            log.error("Execution {} could not be started", executionId, e);
            return;
        }
        log.info("Execution {} of '{}' {} at step #{}", executionId, snapshot.workflowName(),
                resumeOutcome == null ? "started" : "resumed with " + resumeOutcome, snapshot.currentStepIndex());

        ExecutionContext context = snapshot.context();
        try {
            runLoop(snapshot, context, resumeOutcome);
        } catch (RuntimeException e) {
            log.error("Execution {} aborted by infrastructure error", executionId, e);
            markFailedQuietly(snapshot, context, "Infrastructure error: " + e.getMessage());
        }
    }

    private void runLoop(ExecutionSnapshot snapshot, ExecutionContext context, String injectedOutcome) {
        WorkflowDefinition definition = snapshot.definition();
        UUID executionId = snapshot.executionId();
        int index = snapshot.currentStepIndex();

        while (true) {
            StepDefinition step = definition.step(index);
            StepAttempt attempt = injectedOutcome != null
                    ? injected(index, step, injectedOutcome)
                    : executeWithRetries(index, step, context);
            injectedOutcome = null;
            ExecutionResult result = attempt.result();

            if (result.isWaiting()) {
                state.waiting(executionId, attempt, context);
                log.info("Execution {} parked at '{}': {}", executionId, step.id(), result.message());
                return;
            }
            if (result.isFailed()) {
                fail(snapshot, attempt, context, result.message());
                return;
            }
            context.recordStepOutput(step.id(), result.output());

            Transition next;
            try {
                next = definition.resolveTransition(index, result.outcome());
            } catch (WorkflowDefinitionException e) {
                fail(snapshot, attempt, context, e.getMessage());
                return;
            }
            switch (next) {
                case Transition.Complete ignored -> {
                    state.completed(executionId, attempt, context);
                    metrics.recordCompletion(snapshot.workflowName(), "COMPLETED", elapsedSince(snapshot.startedAt()));
                    log.info("Execution {} COMPLETED after '{}'", executionId, step.id());
                    return;
                }
                case Transition.Advance advance -> {
                    state.stepCompleted(executionId, attempt, context, advance.stepIndex(),
                            definition.step(advance.stepIndex()).id());
                    index = advance.stepIndex();
                }
            }
        }
    }

    private StepAttempt executeWithRetries(int index, StepDefinition step, ExecutionContext context) {
        Instant started = clock.instant();
        StepExecutor executor = executors.get(step.type());
        if (executor == null) {
            return new StepAttempt(index, step,
                    ExecutionResult.failure("No executor registered for step type " + step.type()), 0, started, started);
        }
        RetryPolicy retry = step.retry();
        ExecutionResult result = null;
        int attempt = 0;
        while (attempt < retry.maxAttempts()) {
            attempt++;
            sleep(retry.delayBeforeAttempt(attempt));
            result = tryExecute(executor, step, context, attempt);
            if (!result.isFailed()) {
                break;
            }
            if (attempt < retry.maxAttempts()) {
                metrics.recordStepRetry(step.type().name());
            }
        }
        return new StepAttempt(index, step, result, attempt, started, clock.instant());
    }

    private static ExecutionResult tryExecute(StepExecutor executor, StepDefinition step, ExecutionContext context, int attempt) {
        try {
            ExecutionResult result = executor.execute(step, context);
            return result != null ? result : ExecutionResult.failure("Executor for " + step.type() + " returned null");
        } catch (RuntimeException e) {
            log.warn("Step '{}' attempt {} failed: {}", step.id(), attempt, e.toString());
            return ExecutionResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private StepAttempt injected(int index, StepDefinition step, String outcome) {
        Instant now = clock.instant();
        return new StepAttempt(index, step, ExecutionResult.completed(outcome, Map.of()), 1, now, now);
    }

    private void fail(ExecutionSnapshot snapshot, StepAttempt attempt, ExecutionContext context, String reason) {
        state.failed(snapshot.executionId(), attempt, context, reason);
        metrics.recordFailure(snapshot.workflowName(), attempt.step().type().name(), "step_failed");
        metrics.recordCompletion(snapshot.workflowName(), "FAILED", elapsedSince(snapshot.startedAt()));
        log.warn("Execution {} FAILED at '{}': {}", snapshot.executionId(), attempt.step().id(), reason);
    }

    private void markFailedQuietly(ExecutionSnapshot snapshot, ExecutionContext context, String reason) {
        try {
            state.failed(snapshot.executionId(), null, context, reason);
            metrics.recordFailure(snapshot.workflowName(), "INFRASTRUCTURE", "infrastructure_error");
        } catch (RuntimeException e) {
            log.error("Execution {} could not be marked FAILED", snapshot.executionId(), e);
        }
    }

    private Duration elapsedSince(Instant start) {
        return Duration.between(start, clock.instant());
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
