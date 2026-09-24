package com.flowforge.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.core.tenant.TenantContext;
import com.flowforge.core.tenant.TenantIsolationViolationException;
import com.flowforge.modules.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Multi-tenancy, RBAC and token rotation against the real security chain and persistence layer. */
class TenantIsolationIT extends AbstractIntegrationTest {

    @Autowired WorkflowRepository workflows;

    private record Tenant(UUID id, String token) {
    }

    private Tenant provisionSecondTenant(String adminToken) {
        String name = "beta-" + UUID.randomUUID();
        ResponseEntity<JsonNode> response = post("/api/v1/tenants", Map.of(
                "name", name, "adminUsername", "badmin", "adminEmail", "badmin@beta.test",
                "adminPassword", "Beta-Secret-Pass-1"), bearerWithIdempotencyKey(adminToken, "prov-" + name));
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.CREATED);
        UUID tenantB = UUID.fromString(response.getBody().get("id").asText());
        return new Tenant(tenantB, login(tenantB, "badmin", "Beta-Secret-Pass-1"));
    }

    @Test
    void tenantsCannotReadEachOthersWorkflows() {
        String tokenA = login(TENANT_ID, ADMIN_USER, ADMIN_PASSWORD);
        UUID workflowA = createWorkflow(tokenA, "secret-" + UUID.randomUUID());
        Tenant b = provisionSecondTenant(tokenA);

        assertThat(get("/api/v1/workflows/" + workflowA, bearer(tokenA)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/workflows/" + workflowA, bearer(b.token())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/workflows/" + workflowA + "/versions", bearer(b.token())).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        JsonNode listing = get("/api/v1/workflows", bearer(b.token())).getBody();
        assertThat(listing.get("totalElements").asInt()).isZero();

        // header/token disagreement is rejected outright
        HttpHeaders spoofed = bearer(b.token());
        spoofed.set("X-Tenant-ID", TENANT_ID.toString());
        ResponseEntity<JsonNode> spoof = get("/api/v1/workflows/" + workflowA, spoofed);
        assertThat(spoof.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(spoof.getBody().get("code").asText()).isEqualTo("TENANT_MISMATCH");
    }

    @Test
    void persistenceLayerBlocksCrossTenantAccessEvenByPrimaryKey() {
        String tokenA = login(TENANT_ID, ADMIN_USER, ADMIN_PASSWORD);
        UUID workflowA = createWorkflow(tokenA, "pk-" + UUID.randomUUID());
        UUID otherTenant = UUID.randomUUID();

        // Hibernate filter hides the row from queries ...
        assertThat(TenantContext.runWith(otherTenant, () -> workflows.findAll())).isEmpty();
        assertThat(TenantContext.runWith(otherTenant, () -> workflows.findTenantIdById(workflowA))).isEmpty();
        // ... and the entity listener rejects loads by primary key, which bypass filters.
        assertThatThrownBy(() -> TenantContext.runWith(otherTenant, () -> workflows.findById(workflowA)))
                .isInstanceOf(TenantIsolationViolationException.class);
        // The owner sees it.
        assertThat(TenantContext.runWith(TENANT_ID, () -> workflows.findById(workflowA))).isPresent();
    }

    @Test
    void permissionsAreEnforcedPerUser() {
        String admin = login(TENANT_ID, ADMIN_USER, ADMIN_PASSWORD);
        String roleName = "viewer-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(post("/api/v1/roles", Map.of("name", roleName, "description", "read only",
                "permissions", java.util.List.of("WORKFLOW_READ")), bearer(admin)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String username = "viewer-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(post("/api/v1/users", Map.of("username", username, "email", username + "@acme.test",
                "password", "Viewer-Secret-Pass-1", "roles", java.util.List.of(roleName)), bearer(admin)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        String viewer = login(TENANT_ID, username, "Viewer-Secret-Pass-1");

        ResponseEntity<JsonNode> denied = post("/api/v1/workflows", Map.of("name", "nope"),
                bearerWithIdempotencyKey(viewer, "k-" + UUID.randomUUID()));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/workflows", bearer(viewer)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/users/me", bearer(viewer)).getBody().get("permissions"))
                .extracting(JsonNode::asText).containsExactly("WORKFLOW_READ");
    }

    @Test
    void refreshTokenRotationDetectsReuse() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-ID", TENANT_ID.toString());
        JsonNode session = post("/api/v1/auth/login", Map.of("username", ADMIN_USER, "password", ADMIN_PASSWORD), headers).getBody();
        String refresh1 = session.get("refreshToken").asText();

        ResponseEntity<JsonNode> rotated = post("/api/v1/auth/refresh", Map.of("refreshToken", refresh1), headers);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String refresh2 = rotated.getBody().get("refreshToken").asText();
        assertThat(refresh2).isNotEqualTo(refresh1);

        // replaying the rotated token revokes the whole family: the newest token stops working too
        assertThat(post("/api/v1/auth/refresh", Map.of("refreshToken", refresh1), headers).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/api/v1/auth/refresh", Map.of("refreshToken", refresh2), headers).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // unauthenticated / missing tenant
        assertThat(get("/api/v1/workflows", new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders noTenant = new HttpHeaders();
        noTenant.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(post("/api/v1/auth/login", Map.of("username", ADMIN_USER, "password", ADMIN_PASSWORD), noTenant)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
