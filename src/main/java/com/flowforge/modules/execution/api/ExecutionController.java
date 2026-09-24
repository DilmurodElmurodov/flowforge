package com.flowforge.modules.execution.api;

import com.flowforge.core.idempotency.Idempotent;
import com.flowforge.core.idempotency.IdempotencyProperties;
import com.flowforge.core.security.UserPrincipal;
import com.flowforge.modules.execution.api.ExecutionDtos.ApprovalRequest;
import com.flowforge.modules.execution.api.ExecutionDtos.ExecutionResponse;
import com.flowforge.modules.execution.api.ExecutionDtos.StartExecutionRequest;
import com.flowforge.modules.execution.api.ExecutionDtos.StepRecordResponse;
import com.flowforge.modules.execution.domain.WorkflowExecution;
import com.flowforge.modules.execution.service.ExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping("/api/v1/executions")
@Tag(name = "Executions")
public class ExecutionController {

    private final ExecutionService executionService;
    private final IdempotencyProperties idempotencyProperties;

    public ExecutionController(ExecutionService executionService, IdempotencyProperties idempotencyProperties) {
        this.executionService = executionService;
        this.idempotencyProperties = idempotencyProperties;
    }

    /**
     * Starts an execution. The {@code X-Idempotency-Key} header doubles as the execution's idempotency key so
     * the HTTP guard and the domain uniqueness constraint agree on the same identity.
     */
    @PostMapping
    @Idempotent
    @PreAuthorize("hasAuthority('WORKFLOW_EXECUTE')")
    @Operation(summary = "Start a workflow execution asynchronously (202 Accepted)")
    public ResponseEntity<ExecutionResponse> start(@Valid @RequestBody StartExecutionRequest request,
                                                   HttpServletRequest httpRequest) {
        String idempotencyKey = httpRequest.getHeader(idempotencyProperties.header()).trim();
        WorkflowExecution execution = executionService.start(request.workflowId(), request.versionNumber(),
                idempotencyKey, request.input());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(execution.getId()).toUri();
        return ResponseEntity.accepted().location(location).body(ExecutionResponse.from(execution));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('EXECUTION_READ')")
    public ResponseEntity<Page<ExecutionResponse>> list(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(executionService.list(pageable).map(ExecutionResponse::from));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'WorkflowExecution', 'EXECUTION_READ')")
    public ResponseEntity<ExecutionResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ExecutionResponse.from(executionService.get(id)));
    }

    @GetMapping("/{id}/steps")
    @PreAuthorize("hasPermission(#id, 'WorkflowExecution', 'EXECUTION_READ')")
    public ResponseEntity<List<StepRecordResponse>> steps(@PathVariable UUID id) {
        return ResponseEntity.ok(executionService.steps(id).stream().map(StepRecordResponse::from).toList());
    }

    @PostMapping("/{id}/approval")
    @PreAuthorize("hasPermission(#id, 'WorkflowExecution', 'EXECUTION_APPROVE')")
    @Operation(summary = "Record an APPROVED/REJECTED decision for a WAITING execution")
    public ResponseEntity<ExecutionResponse> approve(@PathVariable UUID id,
                                                     @Valid @RequestBody ApprovalRequest request,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        WorkflowExecution execution = executionService.approve(id, request.decision(), request.comment(), principal);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ExecutionResponse.from(execution));
    }
}
