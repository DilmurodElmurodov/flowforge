package com.flowforge.modules.execution.service;

import com.flowforge.modules.execution.engine.ExecutionResult;
import com.flowforge.modules.workflow.model.StepDefinition;

import java.time.Instant;

/** Everything the state manager needs to persist about one step attempt. */
public record StepAttempt(int stepIndex, StepDefinition step, ExecutionResult result, int attempts,
                          Instant startedAt, Instant finishedAt) {
}
