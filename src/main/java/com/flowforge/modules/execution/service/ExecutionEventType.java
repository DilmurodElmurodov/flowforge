package com.flowforge.modules.execution.service;

/** Integration event types emitted through the outbox for the {@code WorkflowExecution} aggregate. */
public final class ExecutionEventType {

    public static final String AGGREGATE_TYPE = "WorkflowExecution";

    public static final String REQUESTED = "EXECUTION_REQUESTED";
    public static final String STARTED = "EXECUTION_STARTED";
    public static final String STEP_COMPLETED = "EXECUTION_STEP_COMPLETED";
    public static final String WAITING_APPROVAL = "EXECUTION_WAITING_APPROVAL";
    public static final String RESUMED = "EXECUTION_RESUMED";
    public static final String COMPLETED = "EXECUTION_COMPLETED";
    public static final String FAILED = "EXECUTION_FAILED";

    private ExecutionEventType() {
    }
}
