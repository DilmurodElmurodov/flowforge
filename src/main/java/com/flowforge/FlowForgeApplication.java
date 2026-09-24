package com.flowforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * FlowForge - Enterprise Workflow & Automation Platform.
 *
 * <p>Modular monolith: every bounded context lives under {@code com.flowforge.modules.*} and communicates with
 * other contexts only through well-defined application services or through the transactional outbox / Kafka.
 * Cross-cutting concerns (security, tenancy, idempotency) live under {@code com.flowforge.core.*}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FlowForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowForgeApplication.class, args);
    }
}
