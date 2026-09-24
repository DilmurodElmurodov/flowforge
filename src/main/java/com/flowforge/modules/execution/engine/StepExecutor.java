package com.flowforge.modules.execution.engine;

import com.flowforge.modules.workflow.model.StepDefinition;
import com.flowforge.modules.workflow.model.StepType;

/**
 * Strategy interface: one implementation per {@link StepType}. Implementations must be stateless and
 * thread-safe, must not touch the database, and should express failures through
 * {@link ExecutionResult#failure(String)} (thrown exceptions are treated as failures as well).
 */
public interface StepExecutor {

    StepType type();

    ExecutionResult execute(StepDefinition step, ExecutionContext context);
}
