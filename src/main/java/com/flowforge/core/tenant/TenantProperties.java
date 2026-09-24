package com.flowforge.core.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuration for tenant resolution ({@code flowforge.tenant.*}). */
@ConfigurationProperties(prefix = "flowforge.tenant")
public record TenantProperties(@DefaultValue("X-Tenant-ID") String header) {
}
