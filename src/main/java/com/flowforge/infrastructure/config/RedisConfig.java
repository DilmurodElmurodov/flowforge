package com.flowforge.infrastructure.config;

import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Redis client tuning. Redis backs two latency-critical paths (idempotency locks and consumer deduplication),
 * so command timeouts are kept short: a slow Redis must fail fast rather than stall request threads.
 */
@Configuration
public class RedisConfig {

    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceTimeouts() {
        return builder -> builder.commandTimeout(Duration.ofSeconds(2));
    }
}
