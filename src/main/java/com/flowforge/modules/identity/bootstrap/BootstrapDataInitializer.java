package com.flowforge.modules.identity.bootstrap;

import com.flowforge.modules.identity.repository.TenantRepository;
import com.flowforge.modules.identity.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Provisions the bootstrap tenant on first start (idempotent: skipped when the tenant already exists). */
@Component
@ConditionalOnProperty(prefix = "flowforge.bootstrap", name = "enabled", havingValue = "true")
public class BootstrapDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapDataInitializer.class);

    private final BootstrapProperties properties;
    private final TenantService tenantService;
    private final TenantRepository tenants;

    public BootstrapDataInitializer(BootstrapProperties properties, TenantService tenantService,
                                    TenantRepository tenants) {
        this.properties = properties;
        this.tenantService = tenantService;
        this.tenants = tenants;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.adminPassword() == null || properties.adminPassword().isBlank()) {
            throw new IllegalStateException("flowforge.bootstrap.admin-password must be set when bootstrap is enabled");
        }
        boolean exists = properties.tenantId() != null
                ? tenants.existsById(properties.tenantId())
                : tenants.existsByName(properties.tenantName());
        if (exists) {
            log.info("Bootstrap tenant '{}' already present, skipping provisioning", properties.tenantName());
            return;
        }
        tenantService.provision(properties.tenantId(), properties.tenantName(), properties.adminUsername(),
                properties.adminEmail(), properties.adminPassword());
    }
}
