package com.flowforge.modules.execution.service;

import com.flowforge.modules.execution.ExecutionProperties;
import com.flowforge.modules.execution.domain.ExecutionStatus;
import com.flowforge.modules.execution.domain.WorkflowExecution;
import com.flowforge.modules.execution.engine.WorkflowExecutionEngine;
import com.flowforge.modules.execution.repository.WorkflowExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Self-healing: if the process crashed between committing a PENDING execution and handing it to the engine,
 * the row would otherwise sit in PENDING forever. This job re-dispatches such executions. It runs without a
 * tenant bound (system scope) and re-binds the tenant per execution.
 */
@Component
@ConditionalOnProperty(prefix = "flowforge.execution.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRecoveryJob.class);

    private final WorkflowExecutionRepository executions;
    private final WorkflowExecutionEngine engine;
    private final ExecutionProperties properties;
    private final Clock clock = Clock.systemUTC();

    public ExecutionRecoveryJob(WorkflowExecutionRepository executions, WorkflowExecutionEngine engine,
                                ExecutionProperties properties) {
        this.executions = executions;
        this.engine = engine;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${flowforge.execution.recovery.interval-ms:60000}", initialDelayString = "60000")
    public void redispatchStalled() {
        List<WorkflowExecution> stalled = executions.findStalled(ExecutionStatus.PENDING,
                clock.instant().minus(properties.recovery().stalledAfter()),
                PageRequest.of(0, properties.recovery().batchSize()));
        for (WorkflowExecution execution : stalled) {
            log.warn("Re-dispatching stalled execution {} (tenant {})", execution.getId(), execution.getTenantId());
            engine.execute(execution.getId(), execution.getTenantId());
        }
    }
}
