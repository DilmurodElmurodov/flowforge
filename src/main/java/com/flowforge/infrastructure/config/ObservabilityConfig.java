package com.flowforge.infrastructure.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Observability wiring on top of Spring Boot's auto-configuration:
 * <ul>
 *   <li>Micrometer Tracing bridges to the OpenTelemetry SDK; spans are exported over OTLP
 *       ({@code management.otlp.tracing.endpoint}).</li>
 *   <li>Web MVC, {@code RestClient}, Kafka producer/consumer and JDBC are instrumented automatically; thread pools
 *       propagate context via {@link AsyncConfig}.</li>
 *   <li>{@code @Observed}/{@code @Timed} aspects allow ad-hoc instrumentation of service methods.</li>
 *   <li>Common tags make every metric attributable to the application/instance in Prometheus.</li>
 * </ul>
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags(
            @Value("${spring.application.name:flowforge}") String application) {
        return registry -> registry.config().commonTags("application", application);
    }
}
