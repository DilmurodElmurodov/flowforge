package com.flowforge.core.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Composite resolver iterating over all registered {@link TenantResolver} strategies (ordered). */
@Component
@Primary
public class DelegatingTenantResolver implements TenantResolver {

    private final List<TenantResolver> delegates;

    public DelegatingTenantResolver(List<TenantResolver> delegates) {
        // Exclude ourselves in case Spring injects the primary bean into the list.
        this.delegates = delegates.stream().filter(r -> !(r instanceof DelegatingTenantResolver)).toList();
    }

    @Override
    public Optional<UUID> resolve(HttpServletRequest request) {
        for (TenantResolver delegate : delegates) {
            Optional<UUID> tenant = delegate.resolve(request);
            if (tenant.isPresent()) {
                return tenant;
            }
        }
        return Optional.empty();
    }
}
