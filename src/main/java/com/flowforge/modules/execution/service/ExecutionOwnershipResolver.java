package com.flowforge.modules.execution.service;

import com.flowforge.core.security.rbac.TenantOwnershipResolver;
import com.flowforge.modules.execution.repository.WorkflowExecutionRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class ExecutionOwnershipResolver implements TenantOwnershipResolver {

    private final WorkflowExecutionRepository executions;

    public ExecutionOwnershipResolver(WorkflowExecutionRepository executions) {
        this.executions = executions;
    }

    @Override
    public String targetType() {
        return "WorkflowExecution";
    }

    @Override
    public Optional<UUID> ownerTenantOf(UUID targetId) {
        return executions.findTenantIdById(targetId);
    }
}
