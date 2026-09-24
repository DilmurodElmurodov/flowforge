package com.flowforge.modules.identity.api;

import com.flowforge.core.idempotency.Idempotent;
import com.flowforge.modules.identity.api.IdentityDtos.ProvisionTenantRequest;
import com.flowforge.modules.identity.api.IdentityDtos.TenantResponse;
import com.flowforge.modules.identity.domain.Tenant;
import com.flowforge.modules.identity.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
@Tag(name = "Tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    @Idempotent
    @PreAuthorize("hasAuthority('TENANT_MANAGE')")
    @Operation(summary = "Provision a new tenant with an initial administrator")
    public ResponseEntity<TenantResponse> provision(@Valid @RequestBody ProvisionTenantRequest request) {
        Tenant tenant = tenantService.provision(request.tenantId(), request.name(), request.adminUsername(),
                request.adminEmail(), request.adminPassword());
        return ResponseEntity
                .created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(tenant.getId()).toUri())
                .body(TenantResponse.from(tenant));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TENANT_MANAGE') and #id == principal.tenantId")
    @Operation(summary = "Read the caller's tenant")
    public ResponseEntity<TenantResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(TenantResponse.from(tenantService.get(id)));
    }
}
