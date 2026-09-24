package com.flowforge.modules.identity.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.UUID;

/**
 * First-run bootstrap ({@code flowforge.bootstrap.*}). When enabled and the tenant does not yet exist, a tenant
 * with an ADMIN user is provisioned at start-up so the platform is usable without manual SQL.
 */
@ConfigurationProperties(prefix = "flowforge.bootstrap")
public record BootstrapProperties(
        @DefaultValue("false") boolean enabled,
        UUID tenantId,
        @DefaultValue("default") String tenantName,
        @DefaultValue("admin") String adminUsername,
        @DefaultValue("admin@flowforge.local") String adminEmail,
        String adminPassword) {
}
