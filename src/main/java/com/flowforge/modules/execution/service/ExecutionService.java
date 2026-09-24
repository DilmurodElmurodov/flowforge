package com.flowforge.modules.execution.service;

import org.springframework.beans.factory.annotation.Autowired;
import com.flowforge.core.common.exception.ConflictException;
import com.flowforge.core.common.exception.ResourceNotFoundException;
import com.flowforge.core.security.UserPrincipal;
import com.flowforge.core.tenant.TenantContext;
import com.flowforge.modules.execution.domain.ExecutionStatus;
import com.flowforge.modules.execution.domain.ExecutionStepRecord;
import com.flowforge.modules.execution.domain.WorkflowExecution;
import com.flowforge.modules.execution.engine.ExecutionContext;
import com.flowforge.modules.execution.engine.WorkflowExecutionEngine;
import com.flowforge.modules.execution.engine.executor.ApprovalStepExecutor;
import com.flowforge.modules.execution.repository.ExecutionStepRecordRepository;
import com.flowforge.modules.execution.repository.WorkflowExecutionRepository;
import com.flowforge.modules.outbox.OutboxService;
import com.flowforge.modules.workflow.domain.WorkflowVersion;
import com.flowforge.modules.workflow.model.StepDefinition;
import com.flowforge.modules.workflow.model.StepType;
import com.flowforge.modules.workflow.model.WorkflowDefinition;
import com.flowforge.modules.workflow.service.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Use cases exposed to the API: start, inspect and approve executions.
 *
 * <p>The engine is only kicked off <em>after</em> the creating transaction commits
 * ({@link TransactionSynchronization#afterCommit}); otherwise the worker thread could try to load a row that is
 * not visible yet.
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    public enum ApprovalDecision { APPROVED, REJECTED }

    private final WorkflowExecutionRepository executions;
    private final ExecutionStepRecordRepository stepRecords;
    private final WorkflowService workflowService;
    private final WorkflowExecutionEngine engine;
    private final OutboxService outbox;
    private final Clock clock;

    @Autowired
    public ExecutionService(WorkflowExecutionRepository executions, ExecutionStepRecordRepository stepRecords,
                            WorkflowService workflowService, WorkflowExecutionEngine engine, OutboxService outbox) {
        this(executions, stepRecords, workflowService, engine, outbox, Clock.systemUTC());
    }

    ExecutionService(WorkflowExecutionRepository executions, ExecutionStepRecordRepository stepRecords,
                     WorkflowService workflowService, WorkflowExecutionEngine engine, OutboxService outbox,
                     Clock clock) {
        this.executions = executions;
        this.stepRecords = stepRecords;
        this.workflowService = workflowService;
        this.engine = engine;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Creates a PENDING execution and schedules it once the transaction commits. The same idempotency key
     * within a tenant returns the existing execution, independently of the HTTP-level {@code @Idempotent} guard.
     */
    @Transactional
    public WorkflowExecution start(UUID workflowId, Integer versionNumber, String idempotencyKey,
                                   Map<String, Object> input) {
        UUID tenantId = TenantContext.require();
        Optional<WorkflowExecution> existing = executions.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        WorkflowVersion version = workflowService.resolveExecutable(workflowId, versionNumber);
        WorkflowDefinition definition = workflowService.definitionOf(version);
        WorkflowExecution execution = new WorkflowExecution(version.getWorkflowId(), version.getId(), idempotencyKey,
                ExecutionContext.initialPersistent(input), definition.step(0).id());
        execution = executions.save(execution);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionId", execution.getId());
        payload.put("workflowId", execution.getWorkflowId());
        payload.put("workflowVersionId", execution.getWorkflowVersionId());
        payload.put("versionNumber", version.getVersionNumber());
        payload.put("status", execution.getStatus().name());
        payload.put("occurredAt", clock.instant());
        outbox.append(ExecutionEventType.AGGREGATE_TYPE, execution.getId(), ExecutionEventType.REQUESTED, payload);

        UUID executionId = execution.getId();
        afterCommit(() -> engine.execute(executionId, tenantId));
        return execution;
    }

    /** Records a decision on a WAITING approval step and resumes the engine with the matching outcome. */
    @Transactional
    public WorkflowExecution approve(UUID executionId, ApprovalDecision decision, String comment, UserPrincipal approver) {
        WorkflowExecution execution = get(executionId);
        if (execution.getStatus() != ExecutionStatus.WAITING) {
            throw new ConflictException("EXECUTION_NOT_WAITING",
                    "Execution " + executionId + " is " + execution.getStatus() + " and is not awaiting a decision");
        }
        WorkflowDefinition definition = workflowService.definitionOf(workflowService.loadVersion(execution.getWorkflowVersionId()));
        StepDefinition step = definition.step(execution.getCurrentStepIndex());
        if (step.type() != StepType.APPROVAL) {
            throw new ConflictException("STEP_NOT_APPROVAL", "Current step '" + step.id() + "' is not an approval step");
        }
        String requiredRole = step.configValue(ApprovalStepExecutor.CONFIG_APPROVER_ROLE, null);
        if (requiredRole != null && !approver.hasAuthority("ROLE_" + requiredRole)) {
            throw new AccessDeniedException("Approval requires role '" + requiredRole + "'");
        }

        ExecutionContext context = ExecutionContext.fromPersistent(execution.getId(), execution.getTenantId(),
                execution.getContextData());
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("decision", decision.name());
        record.put("comment", comment);
        record.put("decidedBy", approver.getUsername());
        record.put("decidedAt", clock.instant().toString());
        context.setVariable("approval." + step.id(), record);

        execution.resume();
        execution.updateContext(context.toPersistent());
        executions.save(execution);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionId", execution.getId());
        payload.put("workflowId", execution.getWorkflowId());
        payload.put("stepId", step.id());
        payload.put("decision", decision.name());
        payload.put("decidedBy", approver.getUsername());
        payload.put("occurredAt", clock.instant());
        outbox.append(ExecutionEventType.AGGREGATE_TYPE, execution.getId(), ExecutionEventType.RESUMED, payload);

        UUID tenantId = execution.getTenantId();
        String outcome = decision == ApprovalDecision.APPROVED
                ? ApprovalStepExecutor.OUTCOME_APPROVED : ApprovalStepExecutor.OUTCOME_REJECTED;
        afterCommit(() -> engine.resume(executionId, tenantId, outcome));
        return execution;
    }

    @Transactional(readOnly = true)
    public WorkflowExecution get(UUID executionId) {
        return executions.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowExecution", executionId));
    }

    @Transactional(readOnly = true)
    public Page<WorkflowExecution> list(Pageable pageable) {
        return executions.findAllByTenantIdOrderByCreatedAtDesc(TenantContext.require(), pageable);
    }

    @Transactional(readOnly = true)
    public List<ExecutionStepRecord> steps(UUID executionId) {
        return stepRecords.findByExecutionIdOrderByStartedAtAsc(get(executionId).getId());
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            log.warn("No active transaction synchronisation; running engine hand-off immediately");
            action.run();
        }
    }
}
