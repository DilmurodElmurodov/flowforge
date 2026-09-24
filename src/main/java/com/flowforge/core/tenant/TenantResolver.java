package com.flowforge.core.tenant;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;
import java.util.UUID;

/**
 * Strategy for extracting the tenant from an inbound HTTP request. Implementations are consulted in
 * {@link org.springframework.core.annotation.Order} sequence by {@link DelegatingTenantResolver}; the first
 * non-empty answer wins. Additional strategies (sub-domain, path prefix, API key) can be added as beans
 * without touching the filter.
 */
public interface TenantResolver {

    Optional<UUID> resolve(HttpServletRequest request);
}
