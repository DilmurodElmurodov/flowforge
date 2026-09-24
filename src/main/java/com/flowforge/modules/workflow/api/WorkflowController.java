package com.flowforge.modules.workflow.api;

import com.flowforge.core.idempotency.Idempotent;
import com.flowforge.modules.workflow.api.WorkflowDtos.CreateVersionRequest;
import com.flowforge.modules.workflow.api.WorkflowDtos.CreateWorkflowRequest;
import com.flowforge.modules.workflow.api.WorkflowDtos.WorkflowResponse;
import com.flowforge.modules.workflow.api.WorkflowDtos.WorkflowVersionResponse;
import com.flowforge.modules.workflow.domain.Workflow;
import com.flowforge.modules.workflow.domain.WorkflowVersion;
import com.flowforge.modules.workflow.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflows")
@Tag(name = "Workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping
    @Idempotent
    @PreAuthorize("hasAuthority('WORKFLOW_CREATE')")
    @Operation(summary = "Create a workflow (idempotent via X-Idempotency-Key)")
    public ResponseEntity<WorkflowResponse> create(@Valid @RequestBody CreateWorkflowRequest request) {
        Workflow workflow = workflowService.create(request.name(), request.description());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(workflow.getId()).toUri();
        return ResponseEntity.created(location).body(WorkflowResponse.from(workflow));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('WORKFLOW_READ')")
    public ResponseEntity<Page<WorkflowResponse>> list(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(workflowService.list(pageable).map(WorkflowResponse::from));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Workflow', 'WORKFLOW_READ')")
    public ResponseEntity<WorkflowResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(WorkflowResponse.from(workflowService.get(id)));
    }

    @PostMapping("/{id}/versions")
    @PreAuthorize("hasPermission(#id, 'Workflow', 'WORKFLOW_CREATE')")
    @Operation(summary = "Validate a JSON definition and store it as the next DRAFT version")
    public ResponseEntity<WorkflowVersionResponse> createVersion(@PathVariable UUID id,
                                                                 @Valid @RequestBody CreateVersionRequest request) {
        WorkflowVersion version = workflowService.createVersion(id, request.definition());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{n}")
                .buildAndExpand(version.getVersionNumber()).toUri();
        return ResponseEntity.created(location).body(WorkflowVersionResponse.from(version));
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasPermission(#id, 'Workflow', 'WORKFLOW_READ')")
    public ResponseEntity<List<WorkflowVersionResponse>> listVersions(@PathVariable UUID id) {
        return ResponseEntity.ok(workflowService.listVersions(id).stream().map(WorkflowVersionResponse::from).toList());
    }

    @GetMapping("/{id}/versions/{number}")
    @PreAuthorize("hasPermission(#id, 'Workflow', 'WORKFLOW_READ')")
    public ResponseEntity<WorkflowVersionResponse> getVersion(@PathVariable UUID id, @PathVariable int number) {
        return ResponseEntity.ok(WorkflowVersionResponse.from(workflowService.getVersion(workflowService.get(id).getId(), number)));
    }

    @PostMapping("/{id}/versions/{number}/publish")
    @PreAuthorize("hasPermission(#id, 'Workflow', 'WORKFLOW_PUBLISH')")
    @Operation(summary = "Publish a DRAFT version, deprecating the previously published one")
    public ResponseEntity<WorkflowVersionResponse> publish(@PathVariable UUID id, @PathVariable int number) {
        return ResponseEntity.ok(WorkflowVersionResponse.from(workflowService.publish(id, number)));
    }
}
