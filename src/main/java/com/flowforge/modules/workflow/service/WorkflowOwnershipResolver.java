package com.flowforge.modules.workflow.service;

import com.flowforge.core.security.rbac.TenantOwnershipResolver;
import com.flowforge.modules.workflow.repository.WorkflowRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class WorkflowOwnershipResolver implements TenantOwnershipResolver {

    private final WorkflowRepository workflows;

    public WorkflowOwnershipResolver(WorkflowRepository workflows) {
        this.workflows = workflows;
    }

    @Override
    public String targetType() {
        return "Workflow";
    }

    @Override
    public Optional<UUID> ownerTenantOf(UUID targetId) {
        return workflows.findTenantIdById(targetId);
    }
}
