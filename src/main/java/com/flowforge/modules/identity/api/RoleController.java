package com.flowforge.modules.identity.api;

import com.flowforge.modules.identity.api.IdentityDtos.CreateRoleRequest;
import com.flowforge.modules.identity.api.IdentityDtos.RoleResponse;
import com.flowforge.modules.identity.domain.Role;
import com.flowforge.modules.identity.service.RoleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/v1/roles")
@Tag(name = "Roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public ResponseEntity<RoleResponse> create(@Valid @RequestBody CreateRoleRequest request) {
        Role role = roleService.create(request.name(), request.description(), request.permissions());
        return ResponseEntity
                .created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(role.getId()).toUri())
                .body(RoleResponse.from(role));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_MANAGE', 'USER_MANAGE')")
    public ResponseEntity<List<RoleResponse>> list() {
        return ResponseEntity.ok(roleService.list().stream().map(RoleResponse::from).toList());
    }
}
