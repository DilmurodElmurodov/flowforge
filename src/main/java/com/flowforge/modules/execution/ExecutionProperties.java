package com.flowforge.modules.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Execution runtime settings ({@code flowforge.execution.*}). */
@ConfigurationProperties(prefix = "flowforge.execution")
public record ExecutionProperties(
        @DefaultValue Executor executor,
        @DefaultValue Webhook webhook,
        @DefaultValue Recovery recovery) {

    public record Executor(
            @DefaultValue("4") int corePoolSize,
            @DefaultValue("16") int maxPoolSize,
            @DefaultValue("500") int queueCapacity) {
    }

    public record Webhook(
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("15s") Duration readTimeout,
            @DefaultValue("65536") int maxResponseBytes) {
    }

    public record Recovery(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60000") long intervalMs,
            @DefaultValue("2m") Duration stalledAfter,
            @DefaultValue("20") int batchSize) {
    }
}
