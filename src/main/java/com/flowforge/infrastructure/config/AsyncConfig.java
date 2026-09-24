package com.flowforge.infrastructure.config;

import com.flowforge.core.tenant.TenantThreadLocalAccessor;
import com.flowforge.modules.execution.ExecutionProperties;
import io.micrometer.context.ContextRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Thread pools and context propagation.
 *
 * <p>Every task submitted to {@code workflowExecutor} is wrapped by {@link ContextPropagatingTaskDecorator}, which
 * captures a Micrometer {@code ContextSnapshot} on the submitting thread and restores it on the worker: the
 * OpenTelemetry trace/span (registered by micrometer-tracing) <em>and</em> the tenant (registered here via
 * {@link TenantThreadLocalAccessor}) therefore survive the thread hop.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    static {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new TenantThreadLocalAccessor());
    }

    @Bean(name = "workflowExecutor")
    public ThreadPoolTaskExecutor workflowExecutor(ExecutionProperties properties) {
        ExecutionProperties.Executor settings = properties.executor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("wf-exec-");
        executor.setCorePoolSize(settings.corePoolSize());
        executor.setMaxPoolSize(settings.maxPoolSize());
        executor.setQueueCapacity(settings.queueCapacity());
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        // Back-pressure: when the queue is full the submitting (HTTP) thread runs the task instead of dropping it.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
