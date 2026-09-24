package com.flowforge.modules.workflow.service;

import org.springframework.beans.factory.annotation.Autowired;
import com.flowforge.core.common.exception.BusinessRuleException;
import com.flowforge.core.common.exception.ConflictException;
import com.flowforge.core.common.exception.ResourceNotFoundException;
import com.flowforge.core.tenant.TenantContext;
import com.flowforge.modules.workflow.domain.Workflow;
import com.flowforge.modules.workflow.domain.WorkflowVersion;
import com.flowforge.modules.workflow.domain.WorkflowVersionStatus;
import com.flowforge.modules.workflow.model.WorkflowDefinition;
import com.flowforge.modules.workflow.parser.WorkflowDefinitionParser;
import com.flowforge.modules.workflow.repository.WorkflowRepository;
import com.flowforge.modules.workflow.repository.WorkflowVersionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application service for authoring workflows and managing their versions.
 * Publishing is the only way a definition becomes executable; published versions are immutable, which is what
 * lets running executions safely reference a version id.
 */
@Service
public class WorkflowService {

    private final WorkflowRepository workflows;
    private final WorkflowVersionRepository versions;
    private final WorkflowDefinitionParser parser;
    private final Clock clock;

    @Autowired
    public WorkflowService(WorkflowRepository workflows, WorkflowVersionRepository versions,
                           WorkflowDefinitionParser parser) {
        this(workflows, versions, parser, Clock.systemUTC());
    }

    WorkflowService(WorkflowRepository workflows, WorkflowVersionRepository versions,
                    WorkflowDefinitionParser parser, Clock clock) {
        this.workflows = workflows;
        this.versions = versions;
        this.parser = parser;
        this.clock = clock;
    }

    @Transactional
    public Workflow create(String name, String description) {
        if (workflows.existsByTenantIdAndName(TenantContext.require(), name)) {
            throw new ConflictException("WORKFLOW_EXISTS", "Workflow '" + name + "' already exists");
        }
        return workflows.save(new Workflow(name, description));
    }

    @Transactional(readOnly = true)
    public Workflow get(UUID id) {
        return workflows.findByTenantIdAndId(TenantContext.require(), id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", id));
    }

    @Transactional(readOnly = true)
    public Page<Workflow> list(Pageable pageable) {
        return workflows.findAllByTenantId(TenantContext.require(), pageable);
    }

    /** Validates the JSON definition and stores it as the next DRAFT version of the workflow. */
    @Transactional
    public WorkflowVersion createVersion(UUID workflowId, Map<String, Object> definition) {
        Workflow workflow = get(workflowId);
        if (!workflow.isActive()) {
            throw new BusinessRuleException("WORKFLOW_ARCHIVED", "Archived workflows cannot receive new versions");
        }
        parser.parse(definition);
        int number = workflow.nextVersionNumber();
        workflows.save(workflow);
        return versions.save(new WorkflowVersion(workflow.getId(), number, definition));
    }

    /** Publishes a DRAFT version; any previously published version is deprecated (single active version). */
    @Transactional
    public WorkflowVersion publish(UUID workflowId, int versionNumber) {
        Workflow workflow = get(workflowId);
        WorkflowVersion version = getVersion(workflow.getId(), versionNumber);
        versions.findByWorkflowIdAndStatus(workflow.getId(), WorkflowVersionStatus.PUBLISHED)
                .forEach(previous -> {
                    previous.deprecate();
                    versions.save(previous);
                });
        version.publish(clock.instant());
        return versions.save(version);
    }

    @Transactional(readOnly = true)
    public WorkflowVersion getVersion(UUID workflowId, int versionNumber) {
        return versions.findByWorkflowIdAndVersionNumber(workflowId, versionNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Version " + versionNumber + " of workflow " + workflowId + " was not found"));
    }

    @Transactional(readOnly = true)
    public List<WorkflowVersion> listVersions(UUID workflowId) {
        return versions.findByWorkflowIdOrderByVersionNumberDesc(get(workflowId).getId());
    }

    /**
     * Resolves the version to execute: the requested number (must be published) or, when omitted,
     * the latest published version.
     */
    @Transactional(readOnly = true)
    public WorkflowVersion resolveExecutable(UUID workflowId, Integer versionNumber) {
        Workflow workflow = get(workflowId);
        if (!workflow.isActive()) {
            throw new BusinessRuleException("WORKFLOW_ARCHIVED", "Archived workflows cannot be executed");
        }
        WorkflowVersion version = versionNumber == null
                ? versions.findFirstByWorkflowIdAndStatusOrderByVersionNumberDesc(workflow.getId(), WorkflowVersionStatus.PUBLISHED)
                        .orElseThrow(() -> new BusinessRuleException("NO_PUBLISHED_VERSION",
                                "Workflow " + workflowId + " has no published version"))
                : getVersion(workflow.getId(), versionNumber);
        if (!version.isPublished()) {
            throw new BusinessRuleException("VERSION_NOT_PUBLISHED",
                    "Version " + version.getVersionNumber() + " is " + version.getStatus() + " and cannot be executed");
        }
        return version;
    }

    @Transactional(readOnly = true)
    public WorkflowVersion loadVersion(UUID versionId) {
        return versions.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowVersion", versionId));
    }

    public WorkflowDefinition definitionOf(WorkflowVersion version) {
        return parser.parseTrusted(version.getDefinition());
    }
}
