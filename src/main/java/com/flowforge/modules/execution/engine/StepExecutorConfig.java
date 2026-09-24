package com.flowforge.modules.execution.engine;

import com.flowforge.modules.workflow.model.StepType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code Map<StepType, StepExecutor>} registry from every {@link StepExecutor} bean in the context.
 * Adding a new step type is therefore a matter of adding one bean; the engine itself never changes.
 */
@Configuration
public class StepExecutorConfig {

    @Bean
    public Map<StepType, StepExecutor> stepExecutors(List<StepExecutor> executors) {
        Map<StepType, StepExecutor> registry = new EnumMap<>(StepType.class);
        for (StepExecutor executor : executors) {
            StepExecutor previous = registry.put(executor.type(), executor);
            if (previous != null) {
                throw new IllegalStateException("Multiple StepExecutor beans registered for type " + executor.type()
                        + ": " + previous.getClass().getName() + " and " + executor.getClass().getName());
            }
        }
        for (StepType type : StepType.values()) {
            if (!registry.containsKey(type)) {
                throw new IllegalStateException("No StepExecutor bean registered for step type " + type);
            }
        }
        return Map.copyOf(registry);
    }
}
