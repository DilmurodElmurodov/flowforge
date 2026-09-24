package com.flowforge.core.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Resolves the tenant from the {@code X-Tenant-ID} header (name configurable via {@link TenantProperties}). */
@Component
@Order(0)
public class HeaderTenantResolver implements TenantResolver {

    private final TenantProperties properties;

    public HeaderTenantResolver(TenantProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<UUID> resolve(HttpServletRequest request) {
        String raw = request.getHeader(properties.header());
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException e) {
            throw new TenantResolutionException("Header " + properties.header() + " must be a UUID");
        }
    }
}
