package com.flowforge.modules.workflow.model;

/** Signals an inconsistency in a workflow definition discovered at parse time or at run time. */
public class WorkflowDefinitionException extends RuntimeException {

    public WorkflowDefinitionException(String message) {
        super(message);
    }

    public WorkflowDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
