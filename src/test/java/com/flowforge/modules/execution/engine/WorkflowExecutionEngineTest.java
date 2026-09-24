package com.flowforge.modules.execution.engine;

import com.flowforge.modules.execution.metrics.ExecutionMetrics;
import com.flowforge.modules.execution.service.ExecutionSnapshot;
import com.flowforge.modules.execution.service.ExecutionStateManager;
import com.flowforge.modules.execution.service.StepAttempt;
import com.flowforge.modules.workflow.model.RetryPolicy;
import com.flowforge.modules.workflow.model.StepDefinition;
import com.flowforge.modules.workflow.model.StepType;
import com.flowforge.modules.workflow.model.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionEngineTest {

    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Mock ExecutionStateManager stateManager;
    @Mock ExecutionMetrics metrics;
    @Mock StepExecutor approvalExecutor;
    @Mock StepExecutor emailExecutor;
    @Mock StepExecutor webhookExecutor;

    private WorkflowExecutionEngine engine;

    @BeforeEach
    void setUp() {
        Map<StepType, StepExecutor> executors = Map.of(
                StepType.APPROVAL, approvalExecutor,
                StepType.EMAIL, emailExecutor,
                StepType.HTTP_WEBHOOK, webhookExecutor);
        // Direct executor keeps the test synchronous while still exercising the async entry points.
        engine = new WorkflowExecutionEngine(executors, stateManager, metrics, Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void runsLinearWorkflowToCompletionAndResolvesExecutorsByType() {
        WorkflowDefinition definition = definition(
                step("notify", StepType.EMAIL, Map.of(), null),
                step("hook", StepType.HTTP_WEBHOOK, Map.of(), null));
        when(stateManager.begin(EXECUTION_ID)).thenReturn(snapshot(definition, 0));
        when(emailExecutor.execute(any(), any())).thenReturn(ExecutionResult.success(Map.of("messageId", "m-1")));
        when(webhookExecutor.execute(any(), any())).thenReturn(ExecutionResult.success(Map.of("statusCode", 200)));

        engine.execute(EXECUTION_ID, TENANT_ID).join();

        ArgumentCaptor<StepAttempt> attempt = ArgumentCaptor.forClass(StepAttempt.class);
        verify(stateManager).stepCompleted(eq(EXECUTION_ID), attempt.capture(), any(ExecutionContext.class), eq(1), eq("hook"));
        assertThat(attempt.getValue().step().id()).isEqualTo("notify");
        assertThat(attempt.getValue().result().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<ExecutionContext> context = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(stateManager).completed(eq(EXECUTION_ID), any(StepAttempt.class), context.capture());
        assertThat(context.getValue().stepOutput("notify")).containsEntry("messageId", "m-1");
        assertThat(context.getValue().stepOutput("hook")).containsEntry("statusCode", 200);

        verify(metrics).recordCompletion(eq("test-flow"), eq("COMPLETED"), any(Duration.class));
        verify(stateManager, never()).failed(any(), any(), any(), anyString());
        verifyNoInteractions(approvalExecutor);
    }

    @Test
    void parksExecutionWhenStepReportsWaiting() {
        WorkflowDefinition definition = definition(
                step("review", StepType.APPROVAL, Map.of("approverRole", "MANAGER"), Map.of("APPROVED", "notify", "REJECTED", "END")),
                step("notify", StepType.EMAIL, Map.of(), null));
        when(stateManager.begin(EXECUTION_ID)).thenReturn(snapshot(definition, 0));
        when(approvalExecutor.execute(any(), any())).thenReturn(ExecutionResult.waiting("Awaiting MANAGER"));

        engine.execute(EXECUTION_ID, TENANT_ID).join();

        verify(stateManager).waiting(eq(EXECUTION_ID), any(StepAttempt.class), any(ExecutionContext.class));
        verify(stateManager, never()).stepCompleted(any(), any(), any(), anyInt(), anyString());
        verify(stateManager, never()).completed(any(), any(), any());
        verifyNoInteractions(emailExecutor);
    }

    @Test
    void resumeInjectsExternalOutcomeAndFollowsExplicitTransition() {
        WorkflowDefinition definition = definition(
                step("review", StepType.APPROVAL, Map.of("approverRole", "MANAGER"), Map.of("APPROVED", "notify", "REJECTED", "END")),
                step("notify", StepType.EMAIL, Map.of(), null));
        when(stateManager.snapshot(EXECUTION_ID)).thenReturn(snapshot(definition, 0));

        engine.resume(EXECUTION_ID, TENANT_ID, "REJECTED").join();

        // REJECTED -> END: completes without ever invoking the approval or email executors.
        verify(stateManager).completed(eq(EXECUTION_ID), any(StepAttempt.class), any(ExecutionContext.class));
        verify(stateManager, never()).begin(any());
        verifyNoInteractions(approvalExecutor, emailExecutor);
    }

    @Test
    void resumeWithApprovedContinuesToNextStep() {
        WorkflowDefinition definition = definition(
                step("review", StepType.APPROVAL, Map.of("approverRole", "MANAGER"), Map.of("APPROVED", "notify", "REJECTED", "END")),
                step("notify", StepType.EMAIL, Map.of(), null));
        when(stateManager.snapshot(EXECUTION_ID)).thenReturn(snapshot(definition, 0));
        when(emailExecutor.execute(any(), any())).thenReturn(ExecutionResult.success());

        engine.resume(EXECUTION_ID, TENANT_ID, "APPROVED").join();

        verify(stateManager).stepCompleted(eq(EXECUTION_ID), any(StepAttempt.class), any(ExecutionContext.class), eq(1), eq("notify"));
        verify(emailExecutor).execute(any(), any());
        verify(stateManager).completed(eq(EXECUTION_ID), any(StepAttempt.class), any(ExecutionContext.class));
    }

    @Test
    void retriesFailingStepAccordingToPolicyThenFails() {
        StepDefinition hook = new StepDefinition("hook", null, StepType.HTTP_WEBHOOK, Map.of("url", "http://x"), null,
                new RetryPolicy(3, 0));
        WorkflowDefinition definition = definition(hook);
        when(stateManager.begin(EXECUTION_ID)).thenReturn(snapshot(definition, 0));
        when(webhookExecutor.execute(any(), any())).thenReturn(ExecutionResult.failure("HTTP 503"));

        engine.execute(EXECUTION_ID, TENANT_ID).join();

        verify(webhookExecutor, times(3)).execute(any(), any());
        verify(metrics, times(2)).recordStepRetry("HTTP_WEBHOOK");
        ArgumentCaptor<StepAttempt> attempt = ArgumentCaptor.forClass(StepAttempt.class);
        verify(stateManager).failed(eq(EXECUTION_ID), attempt.capture(), any(ExecutionContext.class), eq("HTTP 503"));
        assertThat(attempt.getValue().attempts()).isEqualTo(3);
        verify(metrics).recordFailure("test-flow", "HTTP_WEBHOOK", "step_failed");
        verify(metrics).recordCompletion(eq("test-flow"), eq("FAILED"), any(Duration.class));
    }

    @Test
    void executorExceptionIsConvertedIntoFailure() {
        WorkflowDefinition definition = definition(step("notify", StepType.EMAIL, Map.of(), null));
        when(stateManager.begin(EXECUTION_ID)).thenReturn(snapshot(definition, 0));
        when(emailExecutor.execute(any(), any())).thenThrow(new IllegalStateException("SMTP down"));

        engine.execute(EXECUTION_ID, TENANT_ID).join();

        verify(stateManager).failed(eq(EXECUTION_ID), any(StepAttempt.class), any(ExecutionContext.class),
                eq("IllegalStateException: SMTP down"));
    }

    @Test
    void unmappedOutcomeFailsTheExecution() {
        WorkflowDefinition definition = definition(
                step("review", StepType.APPROVAL, Map.of("approverRole", "X"), Map.of("APPROVED", "END")),
                step("notify", StepType.EMAIL, Map.of(), null));
        when(stateManager.snapshot(EXECUTION_ID)).thenReturn(snapshot(definition, 0));

        engine.resume(EXECUTION_ID, TENANT_ID, "REJECTED").join();

        verify(stateManager).failed(eq(EXECUTION_ID), any(StepAttempt.class), any(ExecutionContext.class),
                eq("Step 'review' has no transition for outcome 'REJECTED'"));
    }

    @Test
    void infrastructureErrorDuringPersistenceMarksExecutionFailed() {
        WorkflowDefinition definition = definition(step("notify", StepType.EMAIL, Map.of(), null));
        when(stateManager.begin(EXECUTION_ID)).thenReturn(snapshot(definition, 0));
        when(emailExecutor.execute(any(), any())).thenReturn(ExecutionResult.success());
        org.mockito.Mockito.doThrow(new org.springframework.dao.OptimisticLockingFailureException("stale"))
                .when(stateManager).completed(any(), any(), any());

        engine.execute(EXECUTION_ID, TENANT_ID).join();

        verify(stateManager).failed(eq(EXECUTION_ID), isNull(), any(ExecutionContext.class),
                eq("Infrastructure error: stale"));
        verify(metrics).recordFailure("test-flow", "INFRASTRUCTURE", "infrastructure_error");
    }

    // ------------------------------------------------------------------ fixtures

    private static StepDefinition step(String id, StepType type, Map<String, Object> config, Map<String, String> transitions) {
        return new StepDefinition(id, null, type, config, transitions, null);
    }

    private static WorkflowDefinition definition(StepDefinition... steps) {
        return new WorkflowDefinition("test-flow", null, List.of(steps));
    }

    private static ExecutionSnapshot snapshot(WorkflowDefinition definition, int index) {
        return new ExecutionSnapshot(EXECUTION_ID, TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), "test-flow",
                definition, index, ExecutionContext.initial(EXECUTION_ID, TENANT_ID, Map.of("amount", 10)),
                NOW.minusSeconds(5));
    }
}
