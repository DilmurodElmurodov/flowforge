package com.flowforge.modules.execution.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a workflow execution. {@code WAITING} is the parked state used by human-in-the-loop steps
 * (APPROVAL); every other transition is driven by the engine.
 *
 * <pre>
 *   PENDING -> IN_PROGRESS -> COMPLETED
 *                 |  ^            
 *                 v  |            
 *              WAITING            
 *                 |               
 *                 v               
 *               FAILED  (from IN_PROGRESS or WAITING)
 * </pre>
 */
public enum ExecutionStatus {
    PENDING,
    IN_PROGRESS,
    WAITING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    public Set<ExecutionStatus> allowedTransitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(IN_PROGRESS, FAILED);
            case IN_PROGRESS -> EnumSet.of(WAITING, COMPLETED, FAILED);
            case WAITING -> EnumSet.of(IN_PROGRESS, FAILED);
            case COMPLETED, FAILED -> EnumSet.noneOf(ExecutionStatus.class);
        };
    }

    public boolean canTransitionTo(ExecutionStatus target) {
        return allowedTransitions().contains(target);
    }
}
